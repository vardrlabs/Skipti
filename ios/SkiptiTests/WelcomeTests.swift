import SwiftUI
import XCTest
@testable import Skipti

/// D-245 (U9, U17; decisions 159 to 162, 171, 177 to 181, 192): the welcome's gate, flow, progress and words, and the name
/// and picture confirm. Android's twin is WelcomeTest.
final class WelcomeTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("welcome-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func tempStore() -> LedgerStore { LedgerStore(path: dir.appendingPathComponent("v-\(UUID().uuidString).db").path) }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func records(_ store: LedgerStore, _ match: (GroupOpIn) -> Bool) throws -> Int {
        try store.allRecords().filter { record in
            guard let op = try? ffiDecodeGroupOp(blob: record.blob) else { return false }
            return match(op)
        }.count
    }

    private func avatarRecords(_ store: LedgerStore) throws -> Int {
        try records(store) { if case .avatar = $0 { return true }; return false }
    }

    /// `.you` records that carry a name (a new vault's first read writes your id with no name).
    private func youRecords(_ store: LedgerStore) throws -> Int {
        try records(store) { if case .you(_, let name?) = $0 { return !name.isEmpty }; return false }
    }

    /// Fix wave M6: every string literal in Welcome.swift's `enum WelcomeCopy`, read from the source (the table as written,
    /// so a word added to it is scanned without a second list to keep).
    private func copyTableWords() throws -> [String] {
        let source = try appSource("Welcome.swift")
        let start = try XCTUnwrap(source.range(of: "enum WelcomeCopy {"))
        let end = try XCTUnwrap(source.range(of: "\n}\n", range: start.upperBound..<source.endIndex))
        let body = String(source[start.upperBound..<end.lowerBound])
        let literal = try NSRegularExpression(pattern: #""((?:[^"\\]|\\.)*)""#)
        return literal.matches(in: body, range: NSRange(body.startIndex..., in: body)).compactMap {
            Range($0.range(at: 1), in: body).map { String(body[$0]) }
        }
    }

    /// Fix wave M6: a string literal drawn straight into a view (`Text("...")`, `Text(verbatim: "...")`, `Button("...")`,
    /// `GreedyText("...")`), which the copy table scan cannot see.
    static func literalsDrawn(_ source: String) -> [String] {
        let pattern = try! NSRegularExpression(pattern: #"\b(Text|Button|GreedyText)\((verbatim: )?""#)
        return pattern.matches(in: source, range: NSRange(source.startIndex..., in: source)).compactMap {
            Range($0.range, in: source).map { String(source[$0]) }
        }
    }

    /// The launch as SkiptiApp makes it: the vault's read, then whether the welcome shows.
    private func launch(_ store: LedgerStore) throws -> Bool {
        guard case .opened(let before) = VaultRecovery.launch(store) else { throw XCTSkip("the vault did not open") }
        return WelcomeGate.atLaunch(launchedBefore: before) { try WelcomeGate.pending(store) }
    }

    // MARK: - The gate (decision 177)

    func testANewInstallIsWelcomedAndAnOldOneNever() throws {
        let fresh = tempStore()
        XCTAssertTrue(try launch(fresh), "a new install's first launch shows it")
        XCTAssertTrue(try WelcomeGate.pending(fresh), "and the flag is written")
        XCTAssertTrue(try fresh.kvFlag(key: VaultRecovery.launchedFlag))

        // Negative: an install that launched before this build has no flag.
        let old = tempStore()
        try old.setKvFlag(key: VaultRecovery.launchedFlag)
        XCTAssertFalse(try launch(old), "an earlier install never sees the welcome")
        XCTAssertFalse(try WelcomeGate.pending(old), "and gets no flag")

        XCTAssertTrue(WelcomeGate.shouldShow(launchedBefore: false, pending: false))
        XCTAssertTrue(WelcomeGate.shouldShow(launchedBefore: true, pending: true))
        XCTAssertFalse(WelcomeGate.shouldShow(launchedBefore: true, pending: false))
        XCTAssertFalse(WelcomeGate.atLaunch(launchedBefore: true) { throw StoreError.WrongKey }, "an unreadable flag never blocks")
    }

    func testAKilledWelcomeComesBackAndClosingEndsIt() throws {
        let store = tempStore()
        XCTAssertTrue(try launch(store))
        XCTAssertTrue(try launch(store), "killed mid-welcome: the flag is still set, it shows again")
        WelcomeGate.closed(mode: .firstRun) { try WelcomeGate.clear(store) }
        XCTAssertFalse(try WelcomeGate.pending(store))
        XCTAssertFalse(try launch(store), "skip or done ended it for good")
    }

    func testAReplayNeverTouchesTheFlag() throws {
        var cleared = 0
        WelcomeGate.closed(mode: .replay) { cleared += 1 }
        XCTAssertEqual(cleared, 0)
        WelcomeGate.closed(mode: .firstRun) { cleared += 1 }
        XCTAssertEqual(cleared, 1, "first run clears, once")
        XCTAssertFalse(WelcomeFlow.clearsFlag(.replay))
        // The two callers: the root closes first run, About replays.
        let app = try appSource("SkiptiApp.swift")
        XCTAssertTrue(app.contains("WelcomeView(store: LedgerStore.defaultStore(), mode: .firstRun)"))
        XCTAssertTrue(app.contains("WelcomeGate.closed(mode: .firstRun)"))
        let about = try appSource("About.swift")
        XCTAssertTrue(about.contains("WelcomeView(store: LedgerStore.defaultStore(), mode: .replay)"))
        XCTAssertFalse(about.contains("mode: .firstRun"), "About never clears the flag")
        XCTAssertTrue(about.contains("ListRow(title: WelcomeCopy.replay, trailing: .chevron)"), "the row, no summary")
    }

    /// D-245 sabotage pin (I4 survived): the root's first-run close clears the flag on EVERY exit (Skip, Look around
    /// first, Start a group): nothing between the exit and the clear can make it depend on which way out was taken.
    func testEveryExitClearsTheFlagAtTheRoot() throws {
        let app = try appSource("SkiptiApp.swift")
        let close = """
            WelcomeView(store: LedgerStore.defaultStore(), mode: .firstRun) { exit in
                                welcomeExit = exit
                                welcomePending = false
                                WelcomeGate.closed(mode: .firstRun) { try WelcomeGate.clear(LedgerStore.defaultStore()) }
                                welcomeShown = false
                            }
            """
        XCTAssertTrue(app.contains(close), "the first-run close, whole: the clear runs for every exit")
        XCTAssertEqual(app.components(separatedBy: "mode: .firstRun) { exit in").count - 1, 1, "one first-run close")
        // Negative: the exit is only kept for after the cover (`WelcomeLook.after`), never read around the clear.
        let body = try XCTUnwrap(app.components(separatedBy: "mode: .firstRun) { exit in").last?
            .components(separatedBy: "welcomeShown = false").first)
        XCTAssertEqual(body.components(separatedBy: "exit").count - 1, 1, "only `welcomeExit = exit`: \(body)")
    }

    /// The first launch marks the welcome BEFORE it marks the phone launched (a launch killed between them still welcomes).
    func testTheWelcomeIsMarkedBeforeTheLaunch() throws {
        let gate = try appSource("VaultRecoveryView.swift")
        let mark = try XCTUnwrap(gate.range(of: "try WelcomeGate.mark(store)"))
        let launched = try XCTUnwrap(gate.range(of: "try store.setKvFlag(key: launchedFlag)"))
        XCTAssertLessThan(mark.lowerBound, launched.lowerBound)
        let app = try appSource("SkiptiApp.swift")
        let set = try XCTUnwrap(app.range(of: "welcomePending = welcome"))
        let done = try XCTUnwrap(app.range(of: "onboardingComplete = true"))
        XCTAssertLessThan(set.lowerBound, done.lowerBound, "read before the root shows")
        XCTAssertTrue(app.contains("welcomePending && !held && vaultError == nil && !erasedNotice && !verificationArg"),
                      "after the lock, never over the erase notice or a proof")
    }

    // MARK: - The flow

    func testTheFlowRunsW1ToW5AndSkipEndsEveryStepButDone() {
        var seen: [WelcomeStep] = [.welcome]
        while let next = WelcomeFlow.next(after: seen.last!) { seen.append(next) }
        XCTAssertEqual(seen, [.welcome, .look, .name, .picture, .done])
        XCTAssertEqual(WelcomeStep.allCases.map(WelcomeFlow.skippable), [true, true, true, true, false])
        XCTAssertEqual(WelcomeStep.allCases.map(WelcomeFlow.number), [1, 2, 3, 4, nil])
        XCTAssertEqual(WelcomeFlow.spoken(.look), "Step 2 of 4")
        XCTAssertNil(WelcomeFlow.spoken(.done))
        XCTAssertEqual(WelcomeFlow.doneTitle(name: "Sam"), "Nice to meet you, Sam")
        XCTAssertEqual(WelcomeFlow.doneTitle(name: nil), "You're set")
        XCTAssertEqual(WelcomeFlow.doneTitle(name: "   "), "You're set", "a blank name is no name")
    }

    /// Fix wave M8: the page change is spoken, "Step n of 4, <title>" (W5 its title alone); the progress says the same.
    func testTheStepChangeIsSpoken() throws {
        XCTAssertEqual(WelcomeStep.allCases.map { WelcomeFlow.announcement($0, name: "Sam") },
                       ["Step 1 of 4, Welcome to SKIPTI", "Step 2 of 4, Pick a look", "Step 3 of 4, Your name",
                        "Step 4 of 4, Your picture", "Nice to meet you, Sam"])
        XCTAssertEqual(WelcomeFlow.announcement(.done, name: nil), "You're set")
        let source = try appSource("Welcome.swift")
        XCTAssertTrue(source.contains(".onChange(of: step) { _, new in\n            AccessibilityNotification.Announcement(WelcomeFlow.announcement(new, name: savedName)).post()"))
        XCTAssertTrue(source.contains("spoken: WelcomeFlow.announcement(step, name: savedName))"))
        // Negative: the progress is no longer hidden on W5.
        XCTAssertFalse(source.contains(".accessibilityHidden(spoken == nil)"))
    }

    /// Fix wave I2: "Everything stays on this phone." (W1) and "Your name and picture stay on this phone." (W4) show only
    /// without an account, as Profile's footnotes do; a signed-in replay shows neither.
    func testThePhoneOnlyLinesShowOnlyWithoutAnAccount() throws {
        XCTAssertEqual(WelcomeFlow.foot(.welcome, signedIn: false), WelcomeCopy.welcomeFoot)
        XCTAssertEqual(WelcomeFlow.foot(.picture, signedIn: false), WelcomeCopy.pictureFoot)
        XCTAssertEqual(WelcomeStep.allCases.map { WelcomeFlow.foot($0, signedIn: true) }, [nil, nil, nil, nil, nil],
                       "signed in: no foot on any step")
        XCTAssertEqual(ProfileCopy.footnotes(signedIn: true).contains(WelcomeCopy.pictureFoot), false, "Profile's own rule")
        let source = try appSource("Welcome.swift")
        XCTAssertTrue(source.contains("if let foot = WelcomeFlow.foot(.welcome, signedIn: account.isSignedIn) {"))
        XCTAssertTrue(source.contains("if let foot = WelcomeFlow.foot(.picture, signedIn: account.isSignedIn) {"))
        // Negative: neither line is drawn by its name any more.
        XCTAssertFalse(source.contains("Text(verbatim: WelcomeCopy.welcomeFoot)"))
        XCTAssertFalse(source.contains("Text(verbatim: WelcomeCopy.pictureFoot)"))
    }

    /// Fix wave I3: at the largest text the bar grows (never a fixed 44), a place label wraps to two lines, and at the
    /// accessibility sizes the places stack one per row.
    func testTheLargestTextGrowsAndNothingClips() throws {
        XCTAssertEqual(WelcomeFlow.placeRows(stacked: false), [[0, 1, 2], [3, 4]])
        XCTAssertEqual(WelcomeFlow.placeRows(stacked: true), [[0], [1], [2], [3], [4]])
        XCTAssertEqual(WelcomeFlow.placeRows(stacked: true).flatMap { $0 }, Array(0..<5), "every place, in order")
        let source = try appSource("Welcome.swift")
        let bar = try XCTUnwrap(source.components(separatedBy: "private var topBar: some View {").last?
            .components(separatedBy: "private var bottom").first)
        XCTAssertTrue(bar.contains(".frame(minHeight: 44)"))
        XCTAssertFalse(bar.contains(".frame(height: 44)"), "a fixed height clips Skip at the largest size")
        let place = try XCTUnwrap(source.components(separatedBy: "private struct WelcomePlace: View {").last?
            .components(separatedBy: "\n}\n").first)
        XCTAssertTrue(place.contains(".lineLimit(2)"))
        XCTAssertFalse(place.contains(".lineLimit(1)"), "one line cut Settle up")
        XCTAssertTrue(source.contains("let stacked = AppearanceState.shared.contentSizeCategory.isAccessibilityCategory"))
        XCTAssertTrue(source.contains("ForEach(WelcomeFlow.placeRows(stacked: stacked), id: \\.self) { row in"))
        // W4: "More" takes its own row at the accessibility sizes (a seventh of the row cut it to an ellipsis).
        let choices = try XCTUnwrap(try appSource("ProfileConfirm.swift").components(separatedBy: "struct EmojiChoices: View {").last)
        XCTAssertTrue(choices.contains("} else if AppearanceState.shared.contentSizeCategory.isAccessibilityCategory {\n            // Fix wave I3"))
        XCTAssertEqual(choices.components(separatedBy: "                more\n").count - 1, 2, "under the six, or seventh in the row")
    }

    /// Decision 198: back on W2 to W4 only, one step, and the progress keeps what is done.
    func testBackGoesOneStepAndTheProgressKeepsWhatIsDone() throws {
        XCTAssertEqual(WelcomeStep.allCases.map { WelcomeFlow.previous(before: $0) }, [nil, .welcome, .look, .name, nil])
        XCTAssertEqual(WelcomeCopy.back, "Back")
        XCTAssertEqual(WelcomeFlow.shown(completed: 2, before: 3), 3, "back from W4 to W3: three steps stay done")
        XCTAssertEqual(WelcomeFlow.shown(completed: 3, before: 2), 3)
        let source = try appSource("Welcome.swift")
        XCTAssertTrue(source.contains("if let previous = WelcomeFlow.previous(before: step) {"))
        XCTAssertTrue(source.contains(".accessibilityLabel(Text(verbatim: WelcomeCopy.back))"))
        XCTAssertTrue(source.contains("guard to > progressTo else { return }"), "the sweep only goes forward")
        let back = try XCTUnwrap(source.components(separatedBy: "Button { step = previous } label: {").last?
            .components(separatedBy: ".accessibilityLabel").first)
        for banned in ["saveAvatar", "setYourName", "clear(", "stage."] {
            XCTAssertFalse(back.contains(banned), "back undoes nothing: \(banned)")
        }
    }

    /// Decision 199: inside the welcome a save plays the success haptic and posts no toast; Profile's posts its toast.
    @MainActor
    func testTheWelcomesSavesPostNoToastAndProfilesDo() throws {
        let store = tempStore()
        var haptics: [HapticKind] = []
        let center = DoneCenter(gate: SuccessGate(), haptic: { kind, _ in haptics.append(kind) }, announce: { _ in })
        let spy = ProfileSave.Effects(changed: {}, share: {}, bump: {})
        XCTAssertEqual(ProfileSave.name("Sam", store: store, effects: spy, feedback: .haptic, center: center), .saved("Sam"))
        XCTAssertNil(center.current, "no toast in the welcome")
        XCTAssertEqual(haptics, [.success], "the success haptic plays")
        let profile = DoneCenter(gate: SuccessGate(), haptic: { _, _ in }, announce: { _ in })
        try ProfileSave.picture(.emoji("🦊"), store: store, effects: spy, center: profile)
        XCTAssertEqual(profile.current, DoneMoments.line(DoneCopy.pictureSaved), "Profile's toast")
        let welcome = try appSource("Welcome.swift")
        XCTAssertTrue(welcome.contains("ProfileSave.name(name, store: store, feedback: .haptic)"))
        XCTAssertTrue(welcome.contains("ProfileSave.returned(name, saved: savedName, store: store, feedback: .haptic)"))
        XCTAssertTrue(welcome.contains("ProfileSave.answer(.use, stage: &stage, store: store, feedback: .haptic)"))
        XCTAssertFalse(welcome.contains("DoneToastHost"), "no toast host in the welcome")
        let profileSource = try appSource("Profile.swift")
        XCTAssertFalse(profileSource.contains("feedback: .haptic"), "Profile keeps its toast")
    }

    /// P1, P2 (the controller's look): W3's field is the app's carded field: a FieldRow in a ListCard (the whole row
    /// focuses; no underline inside the card).
    func testTheNameFieldIsTheCardedFieldRow() throws {
        let page = try XCTUnwrap(try appSource("Welcome.swift").components(separatedBy: "private var namePage: some View {").last?
            .components(separatedBy: "private var picturePage").first)
        XCTAssertTrue(page.contains("ListCard {\n                FieldRow(WelcomeCopy.namePlaceholder, text: $name, font: TypeRole.body(17))"))
        XCTAssertFalse(page.contains(".hairlineField()"), "no second line inside the card")
        XCTAssertFalse(page.contains("TextField("), "no bare field")
        let row = try appSource("ListRows.swift")
        // D-248: the row's own focus, or its form's.
        XCTAssertTrue(row.contains(".simultaneousGesture(TapGesture().onEnded {\n                if let form { form.focus.wrappedValue = form.id } else { focused = true }"), "the whole row focuses")
    }

    func testOneActionAtTheBottom() {
        XCTAssertEqual(WelcomeFlow.action(.welcome, nameDirty: true, staged: true), .next)
        XCTAssertEqual(WelcomeFlow.action(.look, nameDirty: false, staged: false), .next)
        XCTAssertEqual(WelcomeFlow.action(.name, nameDirty: true, staged: false), .saveName)
        XCTAssertEqual(WelcomeFlow.action(.name, nameDirty: false, staged: true), .next, "a name is not required")
        XCTAssertEqual(WelcomeFlow.action(.picture, nameDirty: false, staged: true), .usePicture)
        XCTAssertEqual(WelcomeFlow.action(.picture, nameDirty: true, staged: false), .next)
        XCTAssertEqual(WelcomeFlow.action(.done, nameDirty: false, staged: false), .finish)
        XCTAssertTrue(NameRule.dirty("Sam", saved: nil))
        XCTAssertFalse(NameRule.dirty(" Sam ", saved: "Sam"), "the saved name, trimmed, is not a change")
        XCTAssertFalse(NameRule.dirty("   ", saved: "Sam"), "a blank field is not a change to save")
        // Decision 200 (kept): W3 hides Next while the field holds an unsaved name, valid or not; Skip and Back remain.
        let tooLong = String(repeating: "a", count: 61)
        XCTAssertEqual(WelcomeFlow.action(.name, nameDirty: NameRule.dirty(tooLong, saved: nil), staged: false), .saveName,
                       "decision 200: no Next beside an unsaved name")
        XCTAssertEqual(WelcomeFlow.action(.name, nameDirty: NameRule.dirty("Ann", saved: "Sam"), staged: false), .saveName)
        XCTAssertTrue(WelcomeFlow.skippable(.name))
        XCTAssertEqual(WelcomeFlow.previous(before: .name), .look)
    }

    // MARK: - Decision 192: the progress

    func testTheProgressIsTheCompletedShare() {
        let shares = WelcomeStep.allCases.map { WelcomeProgress.value(completed: WelcomeFlow.completed($0)) }
        XCTAssertEqual(shares, [0, 0.25, 0.5, 0.75, 1])
        XCTAssertEqual(WelcomeFlow.completed(.name, nameSaved: true), 3, "Save name completes W3")
        XCTAssertEqual(WelcomeFlow.completed(.picture, pictureSaved: true), 4, "Use this picture completes W4")
        XCTAssertEqual(WelcomeProgress.value(completed: 9), 1, "never past the end")
        // Aurora and Vardr Labs: the marks at the quarters' centres, the fill stopping on each step's mark.
        XCTAssertEqual((0..<4).map(WelcomeProgress.position), [0.125, 0.375, 0.625, 0.875])
        XCTAssertEqual((0...4).map { WelcomeProgress.lineFill(Double($0)) }, [0, 0.125, 0.375, 0.625, 0.875])
        XCTAssertEqual(WelcomeProgress.lineFill(1.5), 0.25, accuracy: 1e-9)
        XCTAssertEqual(WelcomeProgress.segmentShare(0, fill: 0.125), 1)
        XCTAssertEqual(WelcomeProgress.segmentShare(1, fill: 0.125), 0)
        XCTAssertEqual(WelcomeProgress.segmentShare(1, fill: 0.25), 0.5, accuracy: 1e-9)
        // Norse (decision 197): ᚠ ᚢ ᚦ ᚨ ᚱ, ᚠ lit from the start, one more per completed step.
        XCTAssertEqual(WelcomeProgress.runes, ["\u{16A0}", "\u{16A2}", "\u{16A6}", "\u{16A8}", "\u{16B1}"])
        XCTAssertEqual((0...4).map { WelcomeProgress.pose(.norse, from: 0, to: Double($0), elapsed: 0, still: true).lit.filter { $0 }.count },
                       [1, 2, 3, 4, 5])
        XCTAssertEqual((0...4).map { WelcomeProgress.pose(.aurora, from: 0, to: Double($0), elapsed: 0, still: true).lit.filter { $0 }.count },
                       [0, 1, 2, 3, 4])
        XCTAssertEqual((0..<4).map { WelcomeProgress.roadShare($0, progress: 2.5) }, [1, 1, 0.5, 0])
    }

    func testAStillFrameIsTheLitEndState() {
        for theme in AppTheme.allCases {
            let still = WelcomeProgress.pose(theme, from: 1, to: 2, elapsed: 0, still: true)
            XCTAssertEqual(still.progress, 2, "\(theme)")
            XCTAssertFalse(still.spark, "\(theme): no spark when still")
            let lit = theme == .norse ? [true, true, true, false, false] : [true, true, false, false]
            XCTAssertEqual(still.lit, lit, "\(theme)")
            XCTAssertEqual(still.glow, lit.map { $0 ? WelcomeProgress.restGlow : 0 }, "\(theme)")
            XCTAssertEqual(Set(still.scale), [1], "\(theme)")
            // Negative: moving, the same moment's first frame has not reached the new mark, and the spark rides.
            let first = WelcomeProgress.pose(theme, from: 1, to: 2, elapsed: 0, still: false)
            XCTAssertEqual(first.progress, 1, "\(theme)")
            XCTAssertEqual(first.lit.filter { $0 }.count, lit.filter { $0 }.count - 1, "\(theme)")
            XCTAssertTrue(first.spark, "\(theme)")
        }
    }

    func testTheRoadAndTheLightsAreOneMotion() throws {
        XCTAssertEqual(try XCTUnwrap(WelcomeProgress.crossing(2, from: 1, to: 2)), WelcomeProgress.sweepSeconds, accuracy: 1e-6,
                       "the mark lights as the sweep lands on it")
        XCTAssertEqual(try XCTUnwrap(WelcomeProgress.crossing(1.5, from: 1, to: 2)), WelcomeProgress.sweepSeconds / 2, accuracy: 1e-6)
        XCTAssertNil(WelcomeProgress.crossing(1, from: 1, to: 2), "a mark lit before is not crossed again")
        let before = WelcomeProgress.pose(.norse, from: 1, to: 2, elapsed: WelcomeProgress.sweepSeconds - 0.01, still: false)
        let after = WelcomeProgress.pose(.norse, from: 1, to: 2, elapsed: WelcomeProgress.sweepSeconds, still: false)
        XCTAssertFalse(before.lit[2])
        XCTAssertTrue(after.lit[2], "ᚦ lights as the road reaches it")
        XCTAssertGreaterThan(after.glow[2], 0.95, "and flares")
        XCTAssertGreaterThan(after.scale[2], 1.1)
        XCTAssertFalse(after.spark, "the spark goes when the sweep lands")
        let settled = WelcomeProgress.pose(.norse, from: 1, to: 2, elapsed: 10, still: false)
        XCTAssertEqual(settled.glow[2], WelcomeProgress.restGlow, accuracy: 1e-3, "then settles, luminous")
        XCTAssertEqual(WelcomeProgress.eased(0.25), 0.125, accuracy: 1e-9, "the mock-up's in-out ease")
        XCTAssertEqual(WelcomeProgress.eased(0.75), 0.875, accuracy: 1e-9)
        // A moment's door: Reduce Motion and Less motion still it, power saving never (D-239).
        let source = try appSource("Welcome.swift")
        XCTAssertTrue(source.contains("MotionClock.momentTicks(reduceMotion: reduceMotion, active: phase == .active)"))
        XCTAssertFalse(source.contains("artTicks"), "not the art's clock")
        XCTAssertFalse(source.contains("PowerState"), "no read of power saving")
        // The founder's look: the glow follows the glyph (a blurred copy), never a round halo. D-246 fix wave (M5, amended
        // on purpose: it was the Text re-blurred every frame): the copy is GlyphGlow's cached mask, sigma 3.2 at 26.
        let runes = try XCTUnwrap(source.components(separatedBy: "struct NorseRunes: View {").last?
            .components(separatedBy: "// MARK: - The screens").first)
        XCTAssertFalse(source.contains("RadialGradient"), "no halo disc")
        XCTAssertTrue(runes.contains("GlyphGlow.view(rune, size: size, blur: WelcomeProgress.runeGlow)\n                                .foregroundStyle(Tokens.ring)\n                                .opacity(pose.glow[i])"))
        XCTAssertFalse(runes.contains(".blur("), "no per-frame blur of a rune")
        XCTAssertEqual(WelcomeProgress.runeGlow, 3.2 / 26, accuracy: 1e-12)
    }

    /// D-246 fix wave (M5): the welcome's lit runes glow from GlyphGlow's cache, so drawing frames builds no mask. The
    /// road rendered at a first frame builds at most its five masks (none if the welcome already drew them this process);
    /// thirty more frames of the sweep and flare, every glow and scale different, build none (the counter does not grow).
    @MainActor
    func testTheWelcomeRunesGlowFromTheCacheAndBuildNothingPerFrame() throws {
        func frame(_ elapsed: Double) throws {
            let pose = WelcomeProgress.pose(.norse, from: 0, to: 4, elapsed: elapsed, still: false)
            XCTAssertTrue(pose.lit.contains(true))
            let renderer = ImageRenderer(content: NorseRunes(pose: pose))
            renderer.scale = 2
            _ = try XCTUnwrap(renderer.cgImage)
        }
        let before = GlyphGlow.builds
        try frame(10)
        let warm = GlyphGlow.builds
        XCTAssertLessThanOrEqual(warm - before, WelcomeProgress.runes.count, "at most one mask a rune")
        for k in 0..<30 { try frame(Double(k) / 30) }
        XCTAssertEqual(GlyphGlow.builds, warm, "no frame builds a mask")
        for rune in WelcomeProgress.runes {
            let m = GlyphGlow.mask(rune, size: WelcomeProgress.runeSize, blur: WelcomeProgress.runeGlow)
            XCTAssertEqual(Double(m.scale), min(3, 6 / 3.2), accuracy: 1e-9, "sigma 3.2 pt")
        }
        XCTAssertEqual(GlyphGlow.builds, warm, "the road's own masks")
    }

    /// Decision 197: the road's geometry is the mock-up's: arches up and down in turn, each ending 14 pt short of the
    /// runes' centres (clear of their ink), nothing past the frame.
    func testTheRoadWindsBetweenTheRunesAndNeverTouchesTheirInk() {
        for i in 0..<WelcomeProgress.steps {
            let r = WelcomeProgress.road(i)
            XCTAssertEqual(r.start.x, WelcomeProgress.runeCentre(i).x + 14)
            XCTAssertEqual(r.end.x, WelcomeProgress.runeCentre(i + 1).x - 14)
            XCTAssertEqual(r.c1.y, WelcomeProgress.baseline + (i % 2 == 0 ? -30 : 30), "up, down, up, down")
            XCTAssertEqual(r.c1.x - r.start.x, 16)
            let mid = WelcomeProgress.roadPoint(i, t: 0.5)
            XCTAssertEqual(abs(mid.y - WelcomeProgress.baseline), 22.5, accuracy: 1e-9, "a cubic's peak is 3/4 of its controls'")
            let a = RuneInk.metrics(WelcomeProgress.runes[i], size: WelcomeProgress.runeSize)
            let b = RuneInk.metrics(WelcomeProgress.runes[i + 1], size: WelcomeProgress.runeSize)
            XCTAssertLessThan(a.ink.width / 2, WelcomeProgress.roadInset, "road \(i) starts clear of its rune's ink")
            XCTAssertLessThan(b.ink.width / 2, WelcomeProgress.roadInset, "road \(i) ends clear of the next rune's ink")
            XCTAssertGreaterThanOrEqual(r.start.x, 0)
            XCTAssertLessThanOrEqual(r.end.x, WelcomeProgress.norseWidth)
        }
        XCTAssertEqual(WelcomeProgress.runeCentre(0).x + WelcomeProgress.runeCentre(4).x, WelcomeProgress.norseWidth,
                       "the row centred in its frame")
        let spark = WelcomeProgress.roadPoint(1, share: 1)
        XCTAssertEqual(spark.x, WelcomeProgress.road(1).end.x, accuracy: 0.01, "the spark reaches the road's end")
    }

    // MARK: - Decisions 192 and 197 (the founder's look): marks measured on the drawn pixels

    private static let scale: CGFloat = 4

    /// A view at `scale`: its width and the alpha of each pixel.
    @MainActor
    private func alpha(_ view: some View) throws -> (width: Int, height: Int, at: (Int, Int) -> UInt8) {
        let renderer = ImageRenderer(content: view)
        renderer.scale = Self.scale
        let cg = try XCTUnwrap(renderer.cgImage)
        let w = cg.width, h = cg.height
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = try XCTUnwrap(CGContext(data: &bytes, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return (w, h, { x, y in bytes[(y * w + x) * 4 + 3] })
    }

    /// Ink boxes (points) found in the rows `rows` (points from the top): horizontal runs of inked columns, each with the
    /// vertical extent of its ink.
    @MainActor
    private func inkBoxes(_ view: some View, rows: ClosedRange<CGFloat>? = nil) throws -> [CGRect] {
        let image = try alpha(view)
        let y0 = rows.map { Int(($0.lowerBound * Self.scale).rounded()) } ?? 0
        let y1 = rows.map { min(Int(($0.upperBound * Self.scale).rounded()), image.height - 1) } ?? image.height - 1
        var boxes: [CGRect] = []
        var x0: Int?, last = -100, top = Int.max, bottom = -1
        func close() {
            guard let s = x0 else { return }
            boxes.append(CGRect(x: CGFloat(s) / Self.scale, y: CGFloat(top) / Self.scale,
                                width: CGFloat(last + 1 - s) / Self.scale, height: CGFloat(bottom + 1 - top) / Self.scale))
        }
        for x in 0..<image.width {
            let inked = (y0...y1).filter { image.at(x, $0) > 96 }
            guard let first = inked.first, let end = inked.last else { continue }
            if x0 != nil, x - last > Int(2 * Self.scale) { close(); x0 = nil; top = Int.max; bottom = -1 }
            if x0 == nil { x0 = x }
            last = x
            top = min(top, first)
            bottom = max(bottom, end)
        }
        close()
        return boxes
    }

    private func assertOn(_ boxes: [CGRect], centres: [CGFloat], end: CGFloat, _ what: String) {
        XCTAssertEqual(boxes.count, centres.count, "\(what): \(boxes)")
        guard boxes.count == centres.count, let firstBox = boxes.first, let lastBox = boxes.last else { return }
        for (i, box) in boxes.enumerated() {
            XCTAssertEqual(box.midX, centres[i], accuracy: 0.5, "\(what): mark \(i) on its place")
        }
        XCTAssertGreaterThan(firstBox.minX, 0, "\(what): nothing before the start")
        XCTAssertLessThan(lastBox.maxX, end, "\(what): nothing past the end")
        XCTAssertEqual(firstBox.minX, end - lastBox.maxX, accuracy: 1.5, "\(what): the row centred")
    }

    /// Norse: every rune's INK centre on its place (within 0.5 pt, across and down), the row inside its frame.
    @MainActor
    func testNorsesRunesSitOnTheirPlacesByTheirInk() throws {
        let unlit = WelcomeProgress.Pose(progress: -1, lit: Array(repeating: false, count: 5), glow: Array(repeating: 0, count: 5),
                                         scale: Array(repeating: 1, count: 5), spark: false)
        let boxes = try inkBoxes(NorseRunes(pose: unlit))
        assertOn(boxes, centres: (0..<5).map { WelcomeProgress.runeCentre($0).x }, end: WelcomeProgress.norseWidth, "Norse")
        for (i, box) in boxes.enumerated() {
            XCTAssertEqual(box.midY, WelcomeProgress.baseline, accuracy: 0.5, "Norse: rune \(i) centred on the road's level")
        }
        // Negative: by the advance, Vardr Runes Carved's bearings move at least one rune off its place.
        let shifts = WelcomeProgress.runes.map { abs(RuneInk.metrics($0, size: WelcomeProgress.runeSize).dx) }
        XCTAssertGreaterThan(shifts.max() ?? 0, 0.5, "the bearings are uneven: centring by the advance would miss")
    }

    @MainActor
    func testAurorasNodesSitOnTheirFractions() throws {
        let unlit = WelcomeProgress.pose(.aurora, from: 0, to: 0, elapsed: 0, still: true)
        let mid = WelcomeProgress.auroraHeight / 2
        // Above the 3 pt line: only the nodes are drawn there.
        let boxes = try inkBoxes(WelcomeProgressDrawing(pose: unlit, theme: .aurora), rows: (mid - WelcomeProgress.dot / 2 + 1)...(mid - 2.5))
        assertOn(boxes, centres: (0..<4).map { WelcomeProgress.width * CGFloat(WelcomeProgress.position($0)) }, end: WelcomeProgress.width, "Aurora")
    }

    @MainActor
    func testVardrLabsSegmentsSitOnTheirFractions() throws {
        let unlit = WelcomeProgress.pose(.vardr, from: 0, to: 0, elapsed: 0, still: true)
        let mid = WelcomeProgress.vardrHeight / 2
        // The middle rows: the segments, clear of the corner brackets.
        let boxes = try inkBoxes(WelcomeProgressDrawing(pose: unlit, theme: .vardr), rows: (mid - 1)...(mid + 1))
        assertOn(boxes, centres: (0..<4).map { WelcomeProgress.width * CGFloat(WelcomeProgress.position($0)) }, end: WelcomeProgress.width, "Vardr Labs")
    }

    // MARK: - Words

    func testTheWordsArePinned() throws {
        let pinned: [(String, String)] = [
            (WelcomeCopy.next, "Next"), (WelcomeCopy.skip, "Skip"), (WelcomeCopy.step(3), "Step 3 of 4"),
            (WelcomeCopy.welcomeTitle, "Welcome to SKIPTI"), (WelcomeCopy.welcomeLine, "Split costs. See who owes whom."),
            (WelcomeCopy.places.joined(separator: "|"), "Balance|Groups|Add|Settle up|You"),
            (WelcomeCopy.welcomeFoot, "Everything stays on this phone."),
            (WelcomeCopy.lookTitle, "Pick a look"), (WelcomeCopy.lookLine, "Change it any time on You, under Appearance."),
            (WelcomeCopy.norseDark, AppearanceCopy.norseAlwaysDark),
            (WelcomeCopy.nameTitle, "Your name"), (WelcomeCopy.nameLine, "Shown on Balance and in your groups."),
            (WelcomeCopy.namePlaceholder, "Your name"), (WelcomeCopy.nameCaption("Sam"), "You will see yourself as \"Sam (You)\"."),
            (WelcomeCopy.saveName, "Save name"), (WelcomeCopy.pictureTitle, "Your picture"),
            (WelcomeCopy.pictureLine, "A photo or an emoji beside your name."), (WelcomeCopy.more, "More"),
            (WelcomeCopy.photo, "Choose a photo"), (WelcomeCopy.use, "Use this picture"),
            (WelcomeCopy.pictureFoot, ProfileCopy.phoneOnly), (WelcomeCopy.doneTitle("Sam"), "Nice to meet you, Sam"),
            (WelcomeCopy.doneTitleNoName, "You're set"), (WelcomeCopy.doneLine, "Start a group for a trip, a flat or a dinner, or keep your own spending in Just me."),
            (WelcomeCopy.startGroup, "Start a group"), (WelcomeCopy.lookAround, "Look around first"),
            (WelcomeCopy.sheetTitle, "Your picture"),
            (WelcomeCopy.groupsPicture, "Your groups will see a small copy of this picture."),
            (WelcomeCopy.groupsName, "Your groups see this name."), (WelcomeCopy.notNow, "Not now"),
            (WelcomeCopy.errEmpty, "Add a name first."), (WelcomeCopy.errLong, "Names can be up to 60 characters."),
            (WelcomeCopy.errChar, "That name has a character SKIPTI can't use."),
            (WelcomeCopy.replay, "Show the welcome again"),
        ]
        for (got, want) in pinned { XCTAssertEqual(got, want) }
        XCTAssertEqual(WelcomeCopy.welcomeLead + " " + WelcomeCopy.wordmark, WelcomeCopy.welcomeTitle)
        XCTAssertEqual(StepCopy.why(.name), WelcomeCopy.nameLine, "the ring's why, reused")
        XCTAssertEqual(StepCopy.why(.picture), WelcomeCopy.pictureLine)
        XCTAssertEqual(WelcomeCopy.nameNotSaved, "Could not save your name.")
        XCTAssertEqual(WelcomeCopy.pictureNotSaved, "Could not save your picture.")
        for word in try pinned.map(\.0) + copyTableWords() {
            XCTAssertFalse(word.contains("\u{2014}") || word.contains("\u{2013}"), "a dash: \(word)")
        }
    }

    /// Plan 1.6 (GUARANTEES #5): no welcome word names an account, sign-in, sync, the cloud, Google, Apple or joining.
    func testNoWelcomeWordNamesAnAccount() throws {
        // Fix wave M6: the whole table as written, plus the other tables the welcome draws from.
        let words = try copyTableWords() + [DoneCopy.nameSaved, DoneCopy.pictureSaved] + AppTheme.allCases.map(\.label)
        XCTAssertGreaterThan(words.count, 40)
        XCTAssertTrue(words.contains("Everything stays on this phone."), "the parse reads the table")
        XCTAssertTrue(words.contains("Step \\(n) of 4"), "templates too")
        XCTAssertEqual(WelcomeG5.hits(words), [])
        // Negative: the scan sees one, whatever its case.
        XCTAssertEqual(WelcomeG5.hits(["Pick a look", "SIGN in to Sync"]), ["SIGN in to Sync"])
        XCTAssertEqual(WelcomeG5.hits(["Join a group"]), ["Join a group"])
        // No word is drawn around the table: every Text and Button in the welcome's views and the confirm reads a name.
        let screens = try XCTUnwrap(try appSource("Welcome.swift").components(separatedBy: "// MARK: - The screens").last)
        XCTAssertEqual(Self.literalsDrawn(screens), [])
        XCTAssertEqual(Self.literalsDrawn(try appSource("ProfileConfirm.swift")), [])
        // Negative: the guard sees a literal.
        XCTAssertEqual(Self.literalsDrawn(#"Text(verbatim: "Sign in") Button("Join") Text(WelcomeCopy.next)"#).count, 2)
        // And no welcome screen routes to sign-in or Join.
        let source = try appSource("Welcome.swift")
        for banned in ["SignInScreen", "JoinScreen", "JoinPasteScreen", "InviteCopy", "ImportFlowScreen", "signIn("] {
            XCTAssertFalse(source.contains(banned), "Welcome.swift: \(banned)")
        }
    }

    // MARK: - The theme tap (decision 160)

    @MainActor
    func testATileTakesAppearancesPathAndClosingIsAppearancesClose() throws {
        var writes: [(String, String)] = []
        WelcomeLook.choose(.norse) { writes.append(($0, $1)) }
        XCTAssertEqual(writes.map(\.0), [AppTheme.storageKey])
        XCTAssertEqual(writes.map(\.1), ["norse"])
        let suite = "welcome-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("vardr", forKey: AppTheme.storageKey)
        var icons: [(AppIconChoice, AppTheme)] = []
        var closes = 0
        WelcomeLook.closed(defaults: defaults, applyIcon: { icons.append(($0, $1)) }, pageClosed: { closes += 1 })
        XCTAssertEqual(icons.map(\.0), [.match], "Match theme when nothing is stored")
        XCTAssertEqual(icons.map(\.1), [.vardr])
        XCTAssertEqual(closes, 1)
        // The same write Appearance's tiles make, and the same hooks its page's close runs.
        let hub = try appSource("AccountHubScreen.swift")
        XCTAssertTrue(hub.contains("SettingsSync.set(AppTheme.storageKey, theme.rawValue)"))
        XCTAssertTrue(hub.contains("AppIconSwitcher.applyIfNeeded(choice: AppIconChoice.parse(iconRaw), theme: AppTheme.parse(themeRaw))"))
        let welcome = try appSource("Welcome.swift")
        XCTAssertTrue(welcome.contains("set: @MainActor (String, String) -> Void = { SettingsSync.set($0, $1) }"))
        XCTAssertTrue(welcome.contains("{ AppIconSwitcher.applyIfNeeded(choice: $0, theme: $1) }"))
        XCTAssertTrue(welcome.contains("pageClosed: @MainActor () -> Void = { SettingsSync.pageClosed() }"))
        // D-250 (amended on purpose): the same write, inside the window's crossfade.
        XCTAssertTrue(welcome.contains("ThemeCard(theme: theme, selected: current == theme) { ThemeCrossfade.change { WelcomeLook.choose(theme) } }"))
    }

    // MARK: - The name (decision 179)

    @MainActor
    func testNamesAreRefusedInWordsAndNothingIsWritten() throws {
        let store = tempStore()
        var shares = 0, changes = 0
        let spy = ProfileSave.Effects(changed: { changes += 1 }, share: { shares += 1 }, bump: {})
        let long = String(repeating: "a", count: 61)
        XCTAssertEqual(ProfileSave.name(long, store: store, effects: spy), .refused("Names can be up to 60 characters."))
        XCTAssertEqual(ProfileSave.name("   ", store: store, effects: spy), .refused("Add a name first."))
        XCTAssertEqual(ProfileSave.name("Sam\u{0007}", store: store, effects: spy), .refused("That name has a character SKIPTI can't use."))
        // 31 emoji are 62 UTF-16 units: the rules' count, so it would not be shared.
        XCTAssertEqual(NameRule.check(String(repeating: "😀", count: 31)), .tooLong)
        XCTAssertNil(try store.loadSelf()?.name, "nothing written")
        XCTAssertEqual(try youRecords(store), 0)
        XCTAssertEqual(shares + changes, 0)
        let sixty = String(repeating: "b", count: 60)
        XCTAssertEqual(ProfileSave.name(sixty, store: store, effects: spy), .saved(sixty))
        XCTAssertEqual(try store.loadSelf()?.name, sixty)
        XCTAssertEqual(try youRecords(store), 1)
        XCTAssertEqual(shares, 1)
        XCTAssertEqual(changes, 1)
        XCTAssertNotNil(ProfileShare.shareable(sixty), "a saved name is always shareable")
        XCTAssertFalse(try appSource("Profile.swift").contains("That name cannot be used"), "no raw core text")
    }

    // MARK: - The picture (decision 161)

    /// Fix wave M6: through the real seam (`ProfileSave.answer`, which Profile's sheet and the welcome's Use call), with a
    /// real vault: Not now writes, nudges and shares nothing; Use writes once; a second Use has nothing staged.
    @MainActor
    func testATapStagesAndOnlyUseWritesOnce() throws {
        let store = tempStore()
        var shares = 0, changes = 0, bumps = 0
        let spy = ProfileSave.Effects(changed: { changes += 1 }, share: { shares += 1 }, bump: { bumps += 1 })
        let center = DoneCenter(gate: SuccessGate(), haptic: { _, _ in }, announce: { _ in })
        var stage = PictureStage()
        stage.choose(.emoji("🦊"))
        stage.choose(.emoji("🐼"))
        XCTAssertEqual(stage.staged, .emoji("🐼"))
        XCTAssertNil(try ProfileSave.answer(.notNow, stage: &stage, store: store, effects: spy, center: center))
        XCTAssertNil(stage.staged)
        XCTAssertNil(stage.done)
        XCTAssertEqual(try avatarRecords(store), 0, "Not now writes nothing")
        XCTAssertEqual(shares + changes + bumps, 0, "and nudges, shares and re-reads nothing")
        XCTAssertNil(center.current, "no toast")
        XCTAssertEqual(try store.loadAvatar(), .none)

        stage.choose(.emoji("🦉"))
        XCTAssertEqual(try ProfileSave.answer(.use, stage: &stage, store: store, effects: spy, center: center), .emoji("🦉"))
        XCTAssertEqual(try avatarRecords(store), 1, "one record")
        XCTAssertEqual(shares, 1, "one share request")
        XCTAssertEqual(changes, 1)
        XCTAssertEqual(try store.loadAvatar(), .emoji("🦉"))
        XCTAssertEqual(stage.done, DoneCopy.pictureSaved)
        // Negative: a second Use (a quick double tap) finds nothing staged and writes nothing.
        XCTAssertNil(try ProfileSave.answer(.use, stage: &stage, store: store, effects: spy, center: center))
        XCTAssertEqual(try avatarRecords(store), 1)
        XCTAssertEqual(shares, 1)
        stage.used(.none)
        XCTAssertEqual(stage.done, DoneCopy.pictureRemoved)

        // Profile's grid and picker stage; the one write is ProfileSave's.
        let profile = try appSource("Profile.swift")
        XCTAssertTrue(profile.contains("Button {\n                            stagePicture(.emoji(e))\n"))
        XCTAssertTrue(profile.contains("stagePicture(.photo(stored))"))
        XCTAssertFalse(profile.contains("setAvatar("), "the old save-on-tap is gone")
        XCTAssertTrue(profile.contains(".sheet(item: $confirming)"))
        // The sheet's two answers are the seam above, and nothing else.
        XCTAssertTrue(profile.contains("onUse: { confirm(.use) }, onNotNow: { confirm(.notNow) })"))
        XCTAssertTrue(profile.contains("if let written = try ProfileSave.answer(answer, stage: &stage, store: store) { avatar = written }"))
        XCTAssertEqual(profile.components(separatedBy: "store.saveAvatar(").count - 1, 0)
        XCTAssertEqual(try appSource("ProfileConfirm.swift").components(separatedBy: "try store.saveAvatar(").count - 1, 1)
        XCTAssertEqual(try appSource("Welcome.swift").components(separatedBy: "saveAvatar(").count - 1, 0)
    }

    // MARK: - Fix wave

    /// Fix wave M1: Return saves through the same seam, but never the saved name again (nothing written, nudged or
    /// shared); an empty field still says "Add a name first.".
    @MainActor
    func testReturnDoesNotSaveAnUnchangedNameAgain() throws {
        let store = tempStore()
        var shares = 0, changes = 0
        let spy = ProfileSave.Effects(changed: { changes += 1 }, share: { shares += 1 }, bump: {})
        XCTAssertEqual(ProfileSave.returned("Sam", saved: nil, store: store, effects: spy), .saved("Sam"))
        XCTAssertEqual(try youRecords(store), 1)
        // Negative: the same name (trimmed) on Return writes nothing.
        XCTAssertNil(ProfileSave.returned(" Sam ", saved: "Sam", store: store, effects: spy))
        XCTAssertEqual(try youRecords(store), 1, "no second record")
        XCTAssertEqual(shares, 1)
        XCTAssertEqual(changes, 1)
        XCTAssertEqual(ProfileSave.returned("  ", saved: "Sam", store: store, effects: spy), .refused(WelcomeCopy.errEmpty))
        XCTAssertEqual(ProfileSave.returned("Ann", saved: "Sam", store: store, effects: spy), .saved("Ann"))
        XCTAssertEqual(try youRecords(store), 2)
        for file in ["Welcome.swift", "Profile.swift"] {
            XCTAssertTrue(try appSource(file).contains(".onSubmit { saveName(returned: true) }"), file)
        }
    }

    /// Fix wave M4: a vault failure is said in words, never the raw error, in the welcome and the picture saves.
    func testFailuresAreSaidInWords() throws {
        let welcome = try appSource("Welcome.swift")
        let confirm = try appSource("ProfileConfirm.swift")
        for (file, source) in [("Welcome.swift", welcome), ("ProfileConfirm.swift", confirm)] {
            XCTAssertFalse(source.contains("String(describing: error)"), file)
            XCTAssertFalse(source.contains("\\(error)"), file)
        }
        XCTAssertTrue(welcome.contains("pictureError = WelcomeCopy.pictureNotSaved"))
        XCTAssertTrue(confirm.contains("return .refused(WelcomeCopy.nameNotSaved)"))
        let profile = try appSource("Profile.swift")
        let saves = try XCTUnwrap(profile.components(separatedBy: "private func confirm(_ answer: PictureAnswer) {").last?
            .components(separatedBy: "private func micro").first)
        XCTAssertEqual(saves.components(separatedBy: "self.error = WelcomeCopy.pictureNotSaved").count - 1, 1)
        XCTAssertFalse(saves.contains("\\(error)"), "no raw error in Profile's picture saves")
    }

    /// Fix wave M7: no dead code left behind (the confirm's unread presentation, the flow's unused step count).
    func testNoDeadCode() throws {
        let confirm = try appSource("ProfileConfirm.swift")
        XCTAssertFalse(confirm.contains("enum Presentation"))
        XCTAssertFalse(confirm.contains("presentation:"))
        let welcome = try appSource("Welcome.swift")
        let flow = try XCTUnwrap(welcome.components(separatedBy: "enum WelcomeFlow {").last?.components(separatedBy: "\n}\n").first)
        XCTAssertFalse(flow.contains("static let steps"))
    }
}
