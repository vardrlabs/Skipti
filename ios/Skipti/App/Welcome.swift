import PhotosUI
import SwiftUI

// D-245 (U9; decisions 159, 160, 162, 171, 176 to 181, 192): the first-run welcome. Five screens, every one but the last
// skippable, shown once on a new install (`WelcomeGate`) and again on request from About ("Show the welcome again"). No
// screen names an account, sign-in, sync or the cloud, and none routes to Join a group (GUARANTEES #5; a test scans
// every word below). Android's twin is Welcome.kt; its parity test reads this file's `WelcomeCopy`.

/// Every word the welcome and the picture confirm show, in one table (Android's `WelcomeCopy` is compared with it).
enum WelcomeCopy {
    static let next = "Next"
    static let skip = "Skip"
    /// Decision 198: W2 to W4's back control, spoken (the glyph is the theme's back chevron).
    static let back = "Back"
    static func step(_ n: Int) -> String { "Step \(n) of 4" }

    // W1
    static let welcomeTitle = "Welcome to SKIPTI"
    /// The title as drawn: a small lead over the wordmark (`welcomeLead + " " + wordmark == welcomeTitle`).
    static let welcomeLead = "Welcome to"
    static let wordmark = "SKIPTI"
    static let welcomeLine = "Split costs. See who owes whom."
    static let places = ["Balance", "Groups", "Add", "Settle up", "You"]
    static let welcomeFoot = "Everything stays on this phone."

    // W2
    static let lookTitle = "Pick a look"
    static let lookLine = "Change it any time on You, under Appearance."
    static let norseDark = "Norse is always dark."

    // W3
    static let nameTitle = "Your name"
    static let nameLine = "Shown on Balance and in your groups."
    static let namePlaceholder = "Your name"
    static func nameCaption(_ name: String) -> String { "You will see yourself as \"\(name) (You)\"." }
    static let saveName = "Save name"

    // W4
    static let pictureTitle = "Your picture"
    static let pictureLine = "A photo or an emoji beside your name."
    static let more = "More"
    static let photo = "Choose a photo"
    static let use = "Use this picture"
    static let pictureFoot = "Your name and picture stay on this phone."

    // W5
    static func doneTitle(_ name: String) -> String { "Nice to meet you, \(name)" }
    static let doneTitleNoName = "You're set"
    static let doneLine = "Start a group for a trip, a flat or a dinner."
    static let startGroup = "Start a group"
    static let lookAround = "Look around first"

    // You > Profile (U17)
    static let sheetTitle = "Your picture"
    static let groupsPicture = "Your groups will see a small copy of this picture."
    static let groupsName = "Your groups see this name."
    static let notNow = "Not now"
    static let errEmpty = "Add a name first."
    static let errLong = "Names can be up to 60 characters."
    static let errChar = "That name has a character SKIPTI can't use."
    static let photoUnreadable = "That photo could not be read."

    // About
    static let replay = "Show the welcome again"

    /// A name or a picture the vault could not store (not a rule: the rules are the three words above). Fixed words,
    /// never the raw error (fix wave M4): New group's opening words.
    static let nameNotSaved = "Could not save your name."
    static let pictureNotSaved = "Could not save your picture."
}

/// GUARANTEES #5 (plan 1.6): the words the welcome never shows. Kept out of `WelcomeCopy`, so a scan of that whole table
/// (fix wave M6: the table as written, not a list kept beside it) reads only the welcome's words.
enum WelcomeG5 {
    /// Case-insensitive.
    static let forbidden = ["account", "sign", "sync", "cloud", "google", "apple", "join"]

    /// The forbidden words a list of strings contains (empty when clean).
    static func hits(_ strings: [String]) -> [String] {
        strings.filter { s in forbidden.contains { s.lowercased().contains($0) } }
    }
}

enum WelcomeStep: Int, CaseIterable, Equatable {
    case welcome, look, name, picture, done
}

/// First run clears the flag when it closes; a replay from About never touches it.
enum WelcomeMode: Equatable {
    case firstRun, replay
}

/// How the welcome closed: Skip, "Look around first", or "Start a group" (which then opens New group).
enum WelcomeExit: Equatable {
    case skipped, lookAround, startGroup
}

/// The one action at the bottom of a step.
enum WelcomeAction: Equatable {
    case next, saveName, usePicture, finish
}

/// D-245: the flow, pure. W1 to W4 carry Skip; the bottom carries one action at a time (the page: Save name while the
/// field holds an unsaved name, Use this picture while one is staged, else Next); W5 finishes. Decision 200: W3 hides
/// Next while the field holds an unsaved name (one action at a time, as mock v2 drew); Skip and Back remain.
enum WelcomeFlow {
    static func next(after step: WelcomeStep) -> WelcomeStep? { WelcomeStep(rawValue: step.rawValue + 1) }

    static func skippable(_ step: WelcomeStep) -> Bool { step != .done }

    /// Decision 198: W2 to W4 go back a step (nothing saved is undone); W1 and Done have no back.
    static func previous(before step: WelcomeStep) -> WelcomeStep? {
        step == .welcome || step == .done ? nil : WelcomeStep(rawValue: step.rawValue - 1)
    }

    /// Decision 198: the progress shows what is DONE: going back never takes a completed step away.
    static func shown(completed: Int, before: Int) -> Int { max(completed, before) }

    /// 1 to 4 on W1 to W4; none on Done.
    static func number(_ step: WelcomeStep) -> Int? { step == .done ? nil : step.rawValue + 1 }

    /// What VoiceOver says for the progress ("Step 2 of 4").
    static func spoken(_ step: WelcomeStep) -> String? { number(step).map(WelcomeCopy.step) }

    /// Each step's title (W5's carries the saved name).
    static func title(_ step: WelcomeStep, name: String?) -> String {
        switch step {
        case .welcome: return WelcomeCopy.welcomeTitle
        case .look: return WelcomeCopy.lookTitle
        case .name: return WelcomeCopy.nameTitle
        case .picture: return WelcomeCopy.pictureTitle
        case .done: return doneTitle(name: name)
        }
    }

    /// Fix wave M8: what VoiceOver says when the page changes, and the progress's label: "Step 2 of 4, Pick a look";
    /// W5 is its title alone.
    static func announcement(_ step: WelcomeStep, name: String?) -> String {
        guard let spoken = spoken(step) else { return title(step, name: name) }
        return "\(spoken), \(title(step, name: name))"
    }

    /// Fix wave I2: W1's and W4's quiet foot says the data stays on this phone, so it shows only WITHOUT an account
    /// (Profile's `ProfileCopy.footnotes(signedIn:)` rule); a signed-in replay shows none.
    static func foot(_ step: WelcomeStep, signedIn: Bool) -> String? {
        guard !signedIn else { return nil }
        switch step {
        case .welcome: return WelcomeCopy.welcomeFoot
        case .picture: return WelcomeCopy.pictureFoot
        case .look, .name, .done: return nil
        }
    }

    /// Fix wave I3: W1's five places, as rows of indices: three then two, or one per row at the accessibility text
    /// sizes (the app's `isAccessibilityCategory` switch; Android's `TextSize.stacks`), where a label is wider than a
    /// column.
    static func placeRows(stacked: Bool) -> [[Int]] {
        stacked ? (0..<5).map { [$0] } : [[0, 1, 2], [3, 4]]
    }

    static func doneTitle(name: String?) -> String {
        guard let name = name.map(NameRule.trimmed), !name.isEmpty else { return WelcomeCopy.doneTitleNoName }
        return WelcomeCopy.doneTitle(name)
    }

    static func action(_ step: WelcomeStep, nameDirty: Bool, staged: Bool) -> WelcomeAction {
        switch step {
        case .welcome, .look: return .next
        case .name: return nameDirty ? .saveName : .next
        case .picture: return staged ? .usePicture : .next
        case .done: return .finish
        }
    }

    /// Decision 192: the steps COMPLETED so far: each step passed, and the one on screen once its own save is done
    /// (Save name on W3, Use this picture on W4). Done is all four.
    static func completed(_ step: WelcomeStep, nameSaved: Bool = false, pictureSaved: Bool = false) -> Int {
        switch step {
        case .welcome: return 0
        case .look: return 1
        case .name: return nameSaved ? 3 : 2
        case .picture: return pictureSaved ? 4 : 3
        case .done: return 4
        }
    }

    static func clearsFlag(_ mode: WelcomeMode) -> Bool { mode == .firstRun }
}

/// D-245 (decision 177): when the welcome shows. A new install's first launch writes `welcome/pending` (in the launch's
/// own mark, BEFORE `onboarding/complete`, so a launch killed between the two still welcomes), and the welcome covers the
/// app while it is set. Closing first run clears it; a replay never touches it; an install that launched before this
/// build has no flag and never sees it.
enum WelcomeGate {
    static let pendingKey = "welcome/pending"

    static func shouldShow(launchedBefore: Bool, pending: Bool) -> Bool { !launchedBefore || pending }

    static func mark(_ store: LedgerStore) throws { try store.setKvFlag(key: pendingKey) }

    static func pending(_ store: LedgerStore) throws -> Bool { try store.kvFlag(key: pendingKey) }

    /// The vault has no delete: a cleared flag is any value but the set one (`kvFlag` reads only `[1]` as set).
    static func clear(_ store: LedgerStore) throws { try store.kvSet(key: pendingKey, value: Data([0])) }

    /// The welcome closed (any exit): first run clears the flag, a replay never does.
    static func closed(mode: WelcomeMode, clear: () throws -> Void) {
        guard WelcomeFlow.clearsFlag(mode) else { return }
        try? clear()
    }

    /// The launch's read once the vault opened. A flag that cannot be read is "not pending": the welcome is optional and
    /// never blocks the app (the vault itself opened; its errors are the recovery screen's).
    static func atLaunch(launchedBefore: Bool, readPending: () throws -> Bool) -> Bool {
        shouldShow(launchedBefore: launchedBefore, pending: (try? readPending()) ?? false)
    }
}

/// D-245 (decision 160, plan 1.3 W2): a theme tile applies the theme at once through the SAME write Appearance's tiles
/// make (`SettingsSync.set`, D-244's setting record when signed in), and closing the welcome is closing Appearance: the
/// app icon follows "Match theme" then (D-216, decision 34), and what was chosen goes up in one push.
enum WelcomeLook {
    @MainActor
    static func choose(_ theme: AppTheme, set: @MainActor (String, String) -> Void = { SettingsSync.set($0, $1) }) {
        set(AppTheme.storageKey, theme.rawValue)
    }

    @MainActor
    static func closed(defaults: UserDefaults = .standard,
                       applyIcon: @MainActor (AppIconChoice, AppTheme) -> Void = { AppIconSwitcher.applyIfNeeded(choice: $0, theme: $1) },
                       pageClosed: @MainActor () -> Void = { SettingsSync.pageClosed() }) {
        applyIcon(AppIconChoice.parse(defaults.string(forKey: AppIconChoice.storageKey)),
                  AppTheme.parse(defaults.string(forKey: AppTheme.storageKey)))
        pageClosed()
    }

    /// After the welcome's cover has gone: the Appearance close, then New group when that was the way out.
    @MainActor
    static func after(_ exit: WelcomeExit?) {
        closed()
        if exit == .startGroup { NotificationCenter.default.post(name: .skiptiStartGroup, object: nil) }
    }
}

extension Notification.Name {
    /// D-245: the welcome's "Start a group": the shell opens New group over Balance.
    static let skiptiStartGroup = Notification.Name("skipti.start-group")
}

// MARK: - Decisions 192 and 197: the progress, a moment that advances as each step is completed

/// D-245 (decisions 192 and 197, the founder's): the welcome's progress, pure, in STEP units: `p` runs 0 to 4, one per
/// completed step, and each completion sweeps `p` up by one with an in-out ease (the mock-up's quadratic, 0.9 s). A mark
/// lights as the sweep reaches it (and flares, then settles to a luminous rest), so the road and the lights are one
/// motion. A MOMENT (D-239): Reduce Motion and Less motion still it (it lands at once, no spark), power saving never.
///
/// Norse (decision 197, "the winding road", `.drafts/mock/norse-progress.html` option B): five runes ᚠ ᚢ ᚦ ᚨ ᚱ (the
/// futhark's opening: finishing the welcome spells it), evenly spaced in a 340 x 92 frame and centred by their INK, the
/// first lit from the start; between each pair a cubic arch, up then down in turn, ending 14 pt short of each rune's
/// centre. Aurora and Vardr Labs (decision 192): four marks at the centres of the line's quarters (1/8 to 7/8), the fill
/// stopping on each step's mark.
enum WelcomeProgress {
    static let steps = 4
    static let sweepSeconds = 0.9
    static let flareSeconds = 0.6
    /// A lit mark's glow at rest and at its flare (the blurred copy's opacity).
    static let restGlow = 0.85
    static let peakGlow = 1.0
    static let popScale = 1.18
    /// The glow's blur radius (a tight copy of the mark's own shape; the mock-up's stdDeviation 3.2).
    static let glowBlur: CGFloat = 3.2

    // Aurora and Vardr Labs: a 240 pt line, four marks.
    static let width: CGFloat = 240
    static let lineMarks = 4
    static let dot: CGFloat = 10
    static let auroraHeight: CGFloat = 30
    static let segmentHeight: CGFloat = 6
    static let segmentGap: CGFloat = 4
    static let vardrHeight: CGFloat = 22

    // Norse: the winding road (the mock-up's own numbers, at scale 1).
    static let norseWidth: CGFloat = 340
    static let norseHeight: CGFloat = 92
    static let runeSize: CGFloat = 26
    static let baseline: CGFloat = 50
    static let runeStart: CGFloat = 30
    static let runeSpacing: CGFloat = 70
    static let roadInset: CGFloat = 14
    static let roadControl: CGFloat = 16
    static let roadRise: CGFloat = 30
    static let trackWidth: CGFloat = 1.6
    static let trackAlpha = 0.75
    /// The road's track: the deep gold (the Norse edge's colour, the mock-up's #6B5426) at 0.75. A road, not a card edge.
    static var trackColor: Color { ThemeArt.norseEdge.opacity(trackAlpha) }
    static let fillWidth: CGFloat = 2.2
    static let sparkRadius: CGFloat = 2.6
    /// ᚠ ᚢ ᚦ ᚨ ᚱ.
    static var runes: [String] { Array(Runes.futhark.prefix(5)) }

    static func marks(_ theme: AppTheme) -> Int { theme == .norse ? runes.count : lineMarks }

    /// The progress at which mark `i` lights: Norse's rune i at i (ᚠ from the start), a node or segment i at i + 1.
    static func litAt(_ i: Int, theme: AppTheme) -> Double { theme == .norse ? Double(i) : Double(i + 1) }

    /// The completed share of the welcome (0, 1/4, 2/4, 3/4, 1).
    static func value(completed: Int) -> Double { Double(min(max(completed, 0), steps)) / Double(steps) }

    /// The mock-up's in-out ease.
    static func eased(_ t: Double) -> Double {
        if t <= 0 { return 0 }
        if t >= 1 { return 1 }
        return t < 0.5 ? 2 * t * t : 1 - pow(-2 * t + 2, 2) / 2
    }

    static func progress(from: Double, to: Double, elapsed: Double) -> Double {
        from + (to - from) * eased(elapsed / sweepSeconds)
    }

    /// When (seconds into the sweep) the progress reaches `at`, or nil when this sweep does not cross it.
    static func crossing(_ at: Double, from: Double, to: Double) -> Double? {
        guard to > from, at > from + 1e-9, at <= to + 1e-9 else { return nil }
        let target = (at - from) / (to - from)
        var lo = 0.0, hi = 1.0
        for _ in 0..<40 {
            let mid = (lo + hi) / 2
            if eased(mid) < target { lo = mid } else { hi = mid }
        }
        return hi * sweepSeconds
    }

    struct Pose: Equatable {
        var progress: Double
        var lit: [Bool]
        var glow: [Double]
        var scale: [Double]
        /// Norse: the spark rides the road's front while it sweeps.
        var spark: Bool
    }

    /// The frame at `elapsed` seconds into the sweep from `from` to `to` (step units) in `theme`; `still` is the end
    /// state at once, every lit mark at its rest glow, no spark.
    static func pose(_ theme: AppTheme, from: Double, to: Double, elapsed: Double, still: Bool) -> Pose {
        let shown = still ? to : progress(from: from, to: to, elapsed: elapsed)
        var pose = Pose(progress: shown, lit: [], glow: [], scale: [],
                        spark: !still && to > from && elapsed < sweepSeconds)
        for i in 0..<marks(theme) {
            let at = litAt(i, theme: theme)
            let lit = at <= shown + 1e-9
            var glow = lit ? restGlow : 0
            var scale = 1.0
            if lit, !still, let crossed = crossing(at, from: from, to: to) {
                let k = exp(-max(0, elapsed - crossed) / (flareSeconds / 3))
                glow = restGlow + (peakGlow - restGlow) * k
                scale = 1 + (popScale - 1) * k
            }
            pose.lit.append(lit)
            pose.glow.append(glow)
            pose.scale.append(scale)
        }
        return pose
    }

    /// Seconds after which nothing moves (the last flare has settled).
    static var settles: Double { sweepSeconds + flareSeconds }

    // MARK: Aurora and Vardr Labs: the line

    /// Where mark `i` (0 to 3) sits on the line: the centre of its quarter.
    static func position(_ i: Int) -> Double { (Double(i) + 0.5) / Double(lineMarks) }

    /// The line's fill at progress `p`: the start, then each step's mark, straight between.
    static func lineFill(_ p: Double) -> Double {
        let clamped = min(max(p, 0), Double(steps))
        let k = Int(clamped.rounded(.down))
        func at(_ step: Int) -> Double { step == 0 ? 0 : position(step - 1) }
        guard k < steps else { return at(steps) }
        return at(k) + (at(k + 1) - at(k)) * (clamped - Double(k))
    }

    /// Vardr Labs: how full segment `i` is at line fill `fill`: it fills between the mark before it (or the start) and its own.
    static func segmentShare(_ i: Int, fill: Double) -> Double {
        let start = i == 0 ? 0 : position(i - 1)
        return min(max((fill - start) / (position(i) - start), 0), 1)
    }

    /// Vardr Labs: segment `i`'s span on the line, its quarter less half a gap each side (centred on its mark).
    static func segment(_ i: Int) -> (x: CGFloat, width: CGFloat) {
        let quarter = width / CGFloat(lineMarks)
        return (quarter * CGFloat(i) + segmentGap / 2, quarter - segmentGap)
    }

    // MARK: Norse: the road

    /// Rune `i`'s ink centre.
    static func runeCentre(_ i: Int) -> CGPoint { CGPoint(x: runeStart + runeSpacing * CGFloat(i), y: baseline) }

    /// Road `i` (from rune i to rune i + 1): its start, two controls and end; up on even roads, down on odd.
    static func road(_ i: Int) -> (start: CGPoint, c1: CGPoint, c2: CGPoint, end: CGPoint) {
        let x0 = runeCentre(i).x + roadInset, x1 = runeCentre(i + 1).x - roadInset
        let y = baseline + (i % 2 == 0 ? -roadRise : roadRise)
        return (CGPoint(x: x0, y: baseline), CGPoint(x: x0 + roadControl, y: y), CGPoint(x: x1 - roadControl, y: y),
                CGPoint(x: x1, y: baseline))
    }

    static func roadPath(_ i: Int) -> Path {
        let r = road(i)
        var path = Path()
        path.move(to: r.start)
        path.addCurve(to: r.end, control1: r.c1, control2: r.c2)
        return path
    }

    /// A point on road `i` at parameter `t` (the cubic's own).
    static func roadPoint(_ i: Int, t: Double) -> CGPoint {
        let r = road(i), u = 1 - t
        let a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t
        return CGPoint(x: a * r.start.x + b * r.c1.x + c * r.c2.x + d * r.end.x,
                       y: a * r.start.y + b * r.c1.y + c * r.c2.y + d * r.end.y)
    }

    /// The point `share` of the way along road `i` by LENGTH (as the fill's trim measures it): where the spark rides.
    static func roadPoint(_ i: Int, share: Double) -> CGPoint {
        let n = 96
        var lengths = [0.0]
        var last = roadPoint(i, t: 0)
        for k in 1...n {
            let p = roadPoint(i, t: Double(k) / Double(n))
            lengths.append(lengths[k - 1] + Double(hypot(p.x - last.x, p.y - last.y)))
            last = p
        }
        let want = min(max(share, 0), 1) * lengths[n]
        guard let k = lengths.firstIndex(where: { $0 >= want }), k > 0 else { return roadPoint(i, t: 0) }
        let f = (want - lengths[k - 1]) / max(lengths[k] - lengths[k - 1], 1e-9)
        return roadPoint(i, t: (Double(k - 1) + f) / Double(n))
    }

    /// How much of road `i` is filled at progress `p`.
    static func roadShare(_ i: Int, progress p: Double) -> Double { min(max(p - Double(i), 0), 1) }
}

/// D-245 (decisions 192, 197, the founder's look): a rune's ink, so a row of Vardr Runes Carved glyphs (uneven side
/// bearings) is centred by what is drawn, not by the advance (CLAUDE.md 10: the tab runes are centred by their ink).
enum RuneInk {
    struct Metrics: Equatable {
        /// The glyph's ink box, from the pen's start (x) on the baseline (y up).
        let ink: CGRect
        let advance: CGFloat
        let ascender: CGFloat
        let lineHeight: CGFloat
        /// How far to move a glyph laid out by its box so its ink centre sits where the box's centre was.
        var dx: CGFloat { advance / 2 - ink.midX }
        var dy: CGFloat { lineHeight / 2 - (ascender - ink.midY) }
    }

    static func metrics(_ rune: String, size: CGFloat) -> Metrics {
        let ui = TypeRole.runeUIFont(size, relativeTo: nil, category: .large)
        let font = ui as CTFont
        var chars = Array(rune.utf16)
        var glyphs = [CGGlyph](repeating: 0, count: chars.count)
        guard CTFontGetGlyphsForCharacters(font, &chars, &glyphs, chars.count), let glyph = glyphs.first else {
            return Metrics(ink: .zero, advance: 0, ascender: ui.ascender, lineHeight: ui.lineHeight)
        }
        var one = [glyph]
        var rect = CGRect.zero
        CTFontGetBoundingRectsForGlyphs(font, .horizontal, &one, &rect, 1)
        var advance = CGSize.zero
        CTFontGetAdvancesForGlyphs(font, .horizontal, &one, &advance, 1)
        return Metrics(ink: rect, advance: advance.width, ascender: ui.ascender, lineHeight: ui.lineHeight)
    }
}

/// Decisions 192 and 197: the progress, drawn in the theme's language. Spoken as the step's announcement ("Step n of 4,
/// <title>", fix wave M8).
struct WelcomeProgressBar: View {
    let from: Double
    let to: Double
    let startedAt: Double
    let spoken: String

    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    @State private var settledFor: Double?

    var body: some View {
        let tick = MotionClock.momentTicks(reduceMotion: reduceMotion, active: phase == .active)
        let moving = tick && settledFor != startedAt
        let theme = AppearanceState.shared.theme
        TimelineView(ArtSchedule(paused: !moving)) { timeline in
            let elapsed = MomentClock.elapsed(tick: tick, now: timeline.date.timeIntervalSinceReferenceDate,
                                              startedAt: startedAt, still: WelcomeProgress.settles)
            WelcomeProgressDrawing(pose: WelcomeProgress.pose(theme, from: from, to: to, elapsed: elapsed, still: !tick),
                                   theme: theme)
        }
        .allowsHitTesting(false)
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: spoken))
        .task(id: startedAt) {
            try? await Task.sleep(for: .seconds(max(0, startedAt + WelcomeProgress.settles - Date().timeIntervalSinceReferenceDate)))
            guard !Task.isCancelled else { return }
            settledFor = startedAt
        }
    }
}

/// One frame of the progress in one theme. Every mark is placed by its centre at its place (Norse: its ink centre).
struct WelcomeProgressDrawing: View {
    let pose: WelcomeProgress.Pose
    let theme: AppTheme

    var body: some View {
        switch theme {
        case .norse:
            ZStack(alignment: .topLeading) {
                NorseRoad(pose: pose)
                NorseRunes(pose: pose)
            }
            .frame(width: WelcomeProgress.norseWidth, height: WelcomeProgress.norseHeight)
        case .aurora: aurora
        case .vardr: vardr
        }
    }

    private let w = WelcomeProgress.width

    private func x(_ i: Int) -> CGFloat { w * CGFloat(WelcomeProgress.position(i)) }

    private var fill: Double { WelcomeProgress.lineFill(pose.progress) }

    private var aurora: some View {
        let d = WelcomeProgress.dot
        let h = WelcomeProgress.auroraHeight
        return ZStack(alignment: .topLeading) {
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 1.5).fill(Tokens.ringTrack).frame(width: w, height: 3)
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(LinearGradient(colors: [Tokens.meterEnd, Tokens.meterStart], startPoint: .leading, endPoint: .trailing))
                    .frame(width: w * CGFloat(fill), height: 3)
            }
            .frame(width: w, height: 3, alignment: .leading)
            .position(x: w / 2, y: h / 2)
            ForEach(0..<WelcomeProgress.lineMarks, id: \.self) { i in
                Circle().fill(pose.lit[i] ? Tokens.ring : Tokens.ringTrack).frame(width: d, height: d)
                    .background {
                        if pose.lit[i] {
                            Circle().fill(Tokens.ring).frame(width: d, height: d)
                                .blur(radius: WelcomeProgress.glowBlur)
                                .opacity(pose.glow[i])
                        }
                    }
                    .scaleEffect(pose.scale[i])
                    .position(x: x(i), y: h / 2)
            }
        }
        .frame(width: w, height: h)
    }

    private var vardr: some View {
        let h = WelcomeProgress.vardrHeight
        return ZStack(alignment: .topLeading) {
            ForEach(0..<WelcomeProgress.lineMarks, id: \.self) { i in
                let span = WelcomeProgress.segment(i)
                let share = WelcomeProgress.segmentShare(i, fill: fill)
                ZStack(alignment: .leading) {
                    Rectangle().fill(Tokens.ringTrack)
                    Rectangle().fill(Tokens.ring).frame(width: span.width * CGFloat(share))
                }
                .frame(width: span.width, height: WelcomeProgress.segmentHeight)
                .background {
                    if pose.lit[i] {
                        Rectangle().fill(Tokens.ring)
                            .blur(radius: WelcomeProgress.glowBlur)
                            .opacity(pose.glow[i])
                    }
                }
                .position(x: span.x + span.width / 2, y: h / 2)
            }
            FrameMarks(inset: 0, length: 6).stroke(Tokens.textDim, lineWidth: 1).frame(width: w, height: h)
        }
        .frame(width: w, height: h)
    }
}

/// Decision 197: the road. Each arch's track in the deep gold, the gold fill running along it by length, round caps, and
/// a small bright spark at its front while it sweeps.
struct NorseRoad: View {
    let pose: WelcomeProgress.Pose

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(0..<WelcomeProgress.steps, id: \.self) { i in
                let path = WelcomeProgress.roadPath(i)
                let share = WelcomeProgress.roadShare(i, progress: pose.progress)
                path.stroke(WelcomeProgress.trackColor,
                            style: StrokeStyle(lineWidth: WelcomeProgress.trackWidth, lineCap: .round))
                if share > 0 {
                    path.trimmedPath(from: 0, to: share)
                        .stroke(Tokens.accent, style: StrokeStyle(lineWidth: WelcomeProgress.fillWidth, lineCap: .round))
                }
                if pose.spark, share > 0, share < 1 {
                    let at = WelcomeProgress.roadPoint(i, share: share)
                    Circle().fill(ThemeArt.diamondTop)
                        .frame(width: 2 * WelcomeProgress.sparkRadius, height: 2 * WelcomeProgress.sparkRadius)
                        .position(at)
                }
            }
        }
        .frame(width: WelcomeProgress.norseWidth, height: WelcomeProgress.norseHeight, alignment: .topLeading)
    }
}

/// Decision 197: the five runes, each placed by its INK centre; a lit rune is luminous: the lighter gold over a blurred
/// copy of the glyph itself (never a disc).
struct NorseRunes: View {
    let pose: WelcomeProgress.Pose

    var body: some View {
        let size = WelcomeProgress.runeSize
        ZStack(alignment: .topLeading) {
            Color.clear
            ForEach(0..<WelcomeProgress.runes.count, id: \.self) { i in
                let rune = WelcomeProgress.runes[i]
                let ink = RuneInk.metrics(rune, size: size)
                let at = WelcomeProgress.runeCentre(i)
                let glyph = Text(verbatim: rune).font(TypeRole.rune(size))
                glyph
                    .foregroundStyle(pose.lit[i] ? ThemeArt.diamondTop : ThemeArt.color("runeUnlit"))
                    .fixedSize()
                    .background {
                        if pose.lit[i] {
                            glyph.foregroundStyle(Tokens.ring).fixedSize()
                                .blur(radius: WelcomeProgress.glowBlur)
                                .opacity(pose.glow[i])
                        }
                    }
                    .scaleEffect(pose.scale[i])
                    .position(x: at.x + ink.dx, y: at.y + ink.dy)
            }
        }
        .frame(width: WelcomeProgress.norseWidth, height: WelcomeProgress.norseHeight)
    }
}

// MARK: - The screens

/// W1's five places: the real bar's glyphs in the current theme (Norse runes, Lucide elsewhere), the + on the accent,
/// You as the person glyph, each in the theme's person tile (Aurora circle, Norse diamond, Vardr Labs square). Fix wave
/// I3: a label wraps to two lines in its column; at the accessibility sizes (`stacked`) the place is a row, its tile
/// then its label, which wraps as far as it needs.
private struct WelcomePlace: View {
    let index: Int
    let stacked: Bool

    var body: some View {
        let theme = AppearanceState.shared.theme
        let kind = TileShape.of(theme, person: true)
        let side = ThemeArt.m("seal")
        let shape = TileClip.shape(kind, size: side)
        let drawn = TileClip.drawnSide(kind, size: side)
        let add = index == 2
        let tile = glyph(theme)
            .foregroundStyle(add ? Tokens.onAccent : (index == 4 ? Tokens.accent : Tokens.ring))
            .frame(width: drawn, height: drawn)
            .background(add ? Tokens.accent : Tokens.accentSoft, in: shape)
            .clipShape(shape)
            .overlay { TileClip.edge(kind) }
            .tileFootprint(kind, size: side)
            .accessibilityHidden(true)
        let label = Text(verbatim: WelcomeCopy.places[index])
            .font(TypeRole.name(TypeScale.detail))
            .foregroundStyle(Tokens.text)
        Group {
            if stacked {
                HStack(spacing: 14) {
                    tile
                    label
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                VStack(spacing: 10) {
                    tile
                    label
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(width: 92)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func glyph(_ theme: AppTheme) -> some View {
        let tabs: [Int: HomeTab] = [0: .balance, 1: .groups, 3: .settle]
        if let tab = tabs[index] {
            if TabIndicator.drawsRunes(theme) {
                Text(verbatim: tab.rune).font(TypeRole.rune(ThemeArt.m("seal") * 0.6))
            } else {
                LucideIcon(glyph: tab.glyph, size: 22)
            }
        } else {
            LucideIcon(glyph: index == 2 ? .plus : .userRound, size: 22)
        }
    }
}

/// D-245: the welcome, full screen over the app (first run) or over About (replay). `onClose` gets the way out; the
/// caller clears the flag (`WelcomeGate.closed`) and runs `WelcomeLook.after` once the cover has gone.
struct WelcomeView: View {
    let store: LedgerStore
    let mode: WelcomeMode
    let onClose: (WelcomeExit) -> Void

    private let account = Account.shared
    @AppStorage(AppTheme.storageKey) private var themeRaw = AppTheme.aurora.rawValue
    @Environment(\.motionReduced) private var reduceMotion

    @State private var step: WelcomeStep = .welcome
    @State private var name = ""
    @State private var savedName: String?
    /// The name the done row confirms; it shows while the field still holds it ("stays until the field changes").
    @State private var nameDoneFor: String?
    @State private var nameError: String?
    @State private var saved: Avatar = .none
    @State private var stage = PictureStage()
    @State private var moreOpen = false
    @State private var photoItem: PhotosPickerItem?
    @State private var pictureError: String?
    /// Decision 192: the progress sweep, from what was shown to what is completed, from when.
    @State private var progressFrom = 0.0
    @State private var progressTo = 0.0
    @State private var progressStart = Date().timeIntervalSinceReferenceDate

    private var nameDone: Bool { nameDoneFor != nil && nameDoneFor == NameRule.trimmed(name) }

    private var completed: Int {
        WelcomeFlow.completed(step, nameSaved: nameDone, pictureSaved: stage.done != nil)
    }

    private var action: WelcomeAction {
        WelcomeFlow.action(step, nameDirty: NameRule.dirty(name, saved: savedName), staged: stage.staged != nil)
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            WelcomeProgressBar(from: progressFrom, to: progressTo, startedAt: progressStart,
                               spoken: WelcomeFlow.announcement(step, name: savedName))
                .padding(.top, 4)
                .padding(.bottom, 8)
            GeometryReader { geo in
                ScrollView {
                    VStack(spacing: 0) {
                        Spacer(minLength: 16)
                        page
                            .id(step)
                            .transition(.opacity)
                        Spacer(minLength: 16)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geo.size.height)
                    .padding(.horizontal, 24)
                }
                .scrollDismissesKeyboard(.interactively)
            }
            bottom
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: step)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .auroraBackground()
        .task { load() }
        .onChange(of: completed) { _, new in advance(to: new) }
        // Fix wave M8: the page changed; VoiceOver says which ("Step 2 of 4, Pick a look").
        .onChange(of: step) { _, new in
            AccessibilityNotification.Announcement(WelcomeFlow.announcement(new, name: savedName)).post()
        }
        .onChange(of: name) { _, _ in nameError = nil }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await MainActor.run {
                    if let data, let stored = ProfileRules.storedPhoto(from: data) {
                        pictureError = nil
                        stage.choose(.photo(stored))
                    } else {
                        pictureError = WelcomeCopy.photoUnreadable
                    }
                    photoItem = nil
                }
            }
        }
    }

    // MARK: Chrome

    private var topBar: some View {
        HStack {
            // Decision 198: back on W2 to W4, mirroring Skip; the theme's back chevron (Lucide's, as every bar's).
            if let previous = WelcomeFlow.previous(before: step) {
                Button { step = previous } label: {
                    LucideIcon(glyph: .chevronLeft, size: 22)
                        .foregroundStyle(Tokens.groundAccent)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: WelcomeCopy.back))
            }
            Spacer()
            if WelcomeFlow.skippable(step) {
                Button { onClose(.skipped) } label: {
                    Text(verbatim: WelcomeCopy.skip)
                        .font(TypeRole.label(TypeScale.inlineTitle))
                        .foregroundStyle(Tokens.groundAccent)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        // Fix wave I3: at least 44 high, taller when Skip grows with the text size (never clipped or overlapping).
        .frame(minHeight: 44)
        .padding(.horizontal, 12)
    }

    @ViewBuilder
    private var bottom: some View {
        switch action {
        case .next:
            Button { goNext() } label: { Text(verbatim: WelcomeCopy.next) }
                .buttonStyle(PrimaryButtonStyle())
        case .saveName:
            Button { saveName() } label: { Text(verbatim: WelcomeCopy.saveName) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(NameRule.check(name) != nil)
        case .usePicture:
            Button { usePicture() } label: { Text(verbatim: WelcomeCopy.use) }
                .buttonStyle(PrimaryButtonStyle())
        case .finish:
            VStack(spacing: 10) {
                Button { onClose(.startGroup) } label: { Text(verbatim: WelcomeCopy.startGroup) }
                    .buttonStyle(PrimaryButtonStyle())
                Button { onClose(.lookAround) } label: { Text(verbatim: WelcomeCopy.lookAround) }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
    }

    @ViewBuilder
    private var page: some View {
        switch step {
        case .welcome: welcomePage
        case .look: lookPage
        case .name: namePage
        case .picture: picturePage
        case .done: donePage
        }
    }

    private func title(_ text: String) -> some View {
        let theme = AppearanceState.shared.theme
        return VStack(spacing: 12) {
            GreedyText(text, font: TypeRole.displayUIFont(TypeScale.lockTitle, weight: TitleStyle.weight(theme),
                                                          category: AppearanceState.shared.contentSizeCategory, theme: theme))
                .foregroundStyle(Tokens.text)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            ornament
        }
    }

    /// Norse's title ornament under every welcome title, narrower than a tab root's.
    @ViewBuilder
    private var ornament: some View {
        if TitleOrnament.shows(AppearanceState.shared.theme) {
            TitleOrnament(above: -TitleOrnament.titleBottomInset).frame(width: 200)
        }
    }

    private func line(_ text: String) -> some View {
        Text(verbatim: text)
            .font(TypeRole.body(TypeScale.prose))
            .foregroundStyle(Tokens.textDim)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: W1 to W5

    private var welcomePage: some View {
        VStack(spacing: 28) {
            VStack(spacing: 6) {
                Text(verbatim: WelcomeCopy.welcomeLead)
                    .font(TypeRole.display(TypeScale.inlineTitle, weight: 600))
                    .foregroundStyle(Tokens.groundAccent)
                Text(verbatim: WelcomeCopy.wordmark)
                    .font(TypeRole.display(TypeScale.welcomeMark, weight: 700))
                    .foregroundStyle(Tokens.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: WelcomeCopy.welcomeTitle))
            .accessibilityAddTraits(.isHeader)
            ornament
            line(WelcomeCopy.welcomeLine)
            // Fix wave I3: one place per row at the accessibility sizes, where a label outgrows its column.
            let stacked = AppearanceState.shared.contentSizeCategory.isAccessibilityCategory
            VStack(spacing: stacked ? 14 : 20) {
                ForEach(WelcomeFlow.placeRows(stacked: stacked), id: \.self) { row in
                    HStack(spacing: 8) { ForEach(row, id: \.self) { WelcomePlace(index: $0, stacked: stacked) } }
                }
            }
            .padding(.top, 8)
            // Fix wave I2: only without an account (a signed-in replay's data does not stay on this phone).
            if let foot = WelcomeFlow.foot(.welcome, signedIn: account.isSignedIn) {
                Text(verbatim: foot)
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.textDim)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
            }
        }
    }

    private var lookPage: some View {
        let current = AppTheme.parse(themeRaw)
        return VStack(spacing: 20) {
            title(WelcomeCopy.lookTitle)
            line(WelcomeCopy.lookLine)
            VStack(spacing: 12) {
                ForEach(AppTheme.allCases) { theme in
                    ThemeCard(theme: theme, selected: current == theme) { WelcomeLook.choose(theme) }
                }
            }
            .padding(.top, 4)
            if current.forcesDark {
                Text(verbatim: WelcomeCopy.norseDark)
                    .font(TypeRole.body(AppearanceCopy.detailSize))
                    .foregroundStyle(Tokens.textDim)
            }
        }
    }

    private var namePage: some View {
        let trimmed = NameRule.trimmed(name)
        return VStack(spacing: 20) {
            title(WelcomeCopy.nameTitle)
            line(WelcomeCopy.nameLine)
            // P1, P2 (the controller's look): the app's carded field, a ListCard holding a FieldRow (the whole row takes
            // the tap and focuses; no underline inside the card), as New group's "Your name".
            ListCard {
                FieldRow(WelcomeCopy.namePlaceholder, text: $name, font: TypeRole.body(17))
                    .textContentType(.name)
            }
            .multilineTextAlignment(.center)
            .submitLabel(.done)
            .onSubmit { saveName(returned: true) }
            if !trimmed.isEmpty {
                Text(verbatim: WelcomeCopy.nameCaption(trimmed))
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.textDim)
                    .multilineTextAlignment(.center)
            }
            if let problem = nameError ?? NameRule.liveProblem(name, saved: savedName)?.message {
                Text(verbatim: problem)
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.owed)
                    .multilineTextAlignment(.center)
            } else if nameDone {
                DoneRow(text: DoneCopy.nameSaved)
            }
        }
    }

    private var picturePage: some View {
        VStack(spacing: 20) {
            title(WelcomeCopy.pictureTitle)
            line(WelcomeCopy.pictureLine)
            PictureConfirm(avatar: stage.staged ?? saved, name: savedName,
                           signedIn: account.isSignedIn, done: stage.done) {
                VStack(spacing: 12) {
                    EmojiChoices(chosen: stage.staged ?? saved, open: moreOpen, onMore: { moreOpen = true }) { e in
                        pictureError = nil
                        stage.choose(.emoji(e))
                    }
                    .glassCard(padding: 10)
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Text(verbatim: WelcomeCopy.photo)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    if let pictureError {
                        Text(verbatim: pictureError)
                            .font(TypeRole.body(TypeScale.detail))
                            .foregroundStyle(Tokens.owed)
                            .multilineTextAlignment(.center)
                    }
                    // Fix wave I2: only without an account, under the signed-in groups line never.
                    if let foot = WelcomeFlow.foot(.picture, signedIn: account.isSignedIn) {
                        Text(verbatim: foot)
                            .font(TypeRole.body(TypeScale.detail))
                            .foregroundStyle(Tokens.textDim)
                            .multilineTextAlignment(.center)
                    }
                }
            }
        }
    }

    private var donePage: some View {
        VStack(spacing: 18) {
            DoneCheck(.screen)
            title(WelcomeFlow.doneTitle(name: savedName))
            line(WelcomeCopy.doneLine)
        }
    }

    // MARK: Actions

    private func load() {
        savedName = (try? store.loadSelf())?.name
        name = savedName ?? ""
        saved = (try? store.loadAvatar()) ?? .none
    }

    private func goNext() {
        guard let next = WelcomeFlow.next(after: step) else { return }
        endEditing()
        step = next
    }

    /// `returned`: the keyboard's Return, which never saves an unchanged name again (fix wave M1).
    private func saveName(returned: Bool = false) {
        // Decision 199: the done row is the done state here; the success haptic plays, no toast.
        let outcome = returned
            ? ProfileSave.returned(name, saved: savedName, store: store, feedback: .haptic)
            : ProfileSave.name(name, store: store, feedback: .haptic)
        switch outcome {
        case nil:
            endEditing()
        case .saved(let stored)?:
            savedName = stored
            name = stored
            nameError = nil
            endEditing()
            nameDoneFor = stored
        case .refused(let words)?:
            nameError = words
        }
    }

    private func usePicture() {
        do {
            guard let written = try ProfileSave.answer(.use, stage: &stage, store: store, feedback: .haptic) else { return }
            saved = written
            moreOpen = false
        } catch {
            // Fix wave M4: in words, never the raw error.
            pictureError = WelcomeCopy.pictureNotSaved
        }
    }

    private func endEditing() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    /// Decision 192: a new completed share sweeps from the one shown now.
    private func advance(to completed: Int) {
        // Decision 198: a step back never takes the progress back.
        let to = Double(WelcomeFlow.shown(completed: min(max(completed, 0), WelcomeProgress.steps), before: Int(progressTo)))
        guard to > progressTo else { return }
        progressFrom = progressTo
        progressTo = to
        progressStart = Date().timeIntervalSinceReferenceDate
    }
}
