import SwiftUI
import XCTest
@testable import Skipti

/// D-224 (E3): the done moments' decisions, copy and the one door to the haptics. Android's twin is DoneMomentsTest.kt,
/// which reads DoneMoments.swift's copy.
final class DoneMomentsTests: XCTestCase {
    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text after the first `from`, up to the first `to` after it.
    private func slice(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    /// The switch is on until the person turns it off; a stored off is off.
    func testTheHapticsSettingIsOnByDefault() {
        XCTAssertEqual(HapticsSetting.storageKey, "appearance.haptics")
        XCTAssertTrue(HapticsSetting.parse(nil), "never touched: on")
        XCTAssertTrue(HapticsSetting.parse(true))
        XCTAssertFalse(HapticsSetting.parse(false), "turned off stays off")
    }

    /// The switch silences every kind; on, every kind plays.
    func testThePlaysDecisionFollowsTheSwitch() {
        XCTAssertEqual(HapticKind.allCases, [.light, .success, .warning])
        for kind in HapticKind.allCases {
            XCTAssertFalse(Haptics.plays(kind, enabled: false), "\(kind) with the switch off")
            XCTAssertTrue(Haptics.plays(kind, enabled: true), "\(kind) with the switch on")
        }
    }

    /// A success within a second of the last one is skipped; the first always plays.
    func testTheHapticGate() {
        XCTAssertEqual(HapticGate.window, 1.0)
        XCTAssertTrue(HapticGate.allows(lastSuccess: nil, now: 50))
        XCTAssertFalse(HapticGate.allows(lastSuccess: 50, now: 50.99), "0.99 s after: skipped")
        XCTAssertFalse(HapticGate.allows(lastSuccess: 50, now: 50), "at once: skipped")
        XCTAssertTrue(HapticGate.allows(lastSuccess: 50, now: 51.0), "1.0 s after: plays")
        XCTAssertTrue(HapticGate.allows(lastSuccess: 50, now: 60))
    }

    /// Small at once; big when the check lands (the pop's start, 0.7 s), at once when still.
    func testTheDoneHapticTiming() {
        XCTAssertEqual(DoneHapticTiming.delay(big: false, still: false), 0)
        XCTAssertEqual(DoneHapticTiming.delay(big: false, still: true), 0)
        XCTAssertEqual(DoneHapticTiming.delay(big: true, still: true), 0, "still: at once")
        XCTAssertEqual(DoneHapticTiming.delay(big: true, still: false), 0.7, "moving: when the check lands")
        XCTAssertEqual(DoneHapticTiming.delay(big: true, still: false), CheckMotion.popAt)
    }

    /// The first named group is the big moment, with the group's detail; a later one is small; a count that could not
    /// be read (0) is never big.
    func testGroupCreatedIsBigOnlyForTheFirst() {
        let detail = ["4 people", "INR"]
        XCTAssertEqual(DoneMoments.groupCreated(namedCountAfter: 1, name: "Goa trip", detailParts: detail),
                       .big(BigMoment(kind: .firstGroup, title: "Group created", parts: ["Goa trip", "4 people", "INR"])))
        XCTAssertEqual(DoneMoments.groupCreated(namedCountAfter: 2, name: "Flat 4B", detailParts: detail),
                       .small(DoneLine(title: "Group created", parts: ["Flat 4B"])))
        XCTAssertEqual(DoneMoments.groupCreated(namedCountAfter: 0, name: "Paris", detailParts: detail),
                       .small(DoneLine(title: "Group created", parts: ["Paris"])), "a failed read: never big without proof")
        XCTAssertEqual(DoneMoments.groupCreated(namedCountAfter: 7, name: "Paris", detailParts: detail),
                       .small(DoneLine(title: "Group created", parts: ["Paris"])))
    }

    /// Where the toast sits: clear of the + when the bar shows, else the page's inset.
    func testTheToastPlace() {
        XCTAssertEqual(ToastPlace.bottom(barShown: true), 104)
        XCTAssertEqual(ToastPlace.bottom(barShown: false), 16)
    }

    /// The words, verbatim (both shells), and none carries an em dash.
    func testTheDoneCopyIsVerbatim() {
        let table: [(String, String)] = [
            (DoneCopy.expenseAdded, "Expense added"),
            (DoneCopy.expenseSaved, "Expense saved"),
            (DoneCopy.groupCreated, "Group created"),
            (DoneCopy.personRemoved("Asha"), "Asha removed"),
            (DoneCopy.groupPictureSaved, "Group picture saved"),
            (DoneCopy.groupPictureRemoved, "Group picture removed"),
            (DoneCopy.pictureSaved, "Picture saved"),
            (DoneCopy.pictureRemoved, "Picture removed"),
            (DoneCopy.nameSaved, "Name saved"),
            (DoneCopy.usernameSaved, "Username saved"),
            (DoneCopy.signedOut, "Signed out"),
            (DoneCopy.settledWith("Asha"), "Settled with Asha"),
            (DoneCopy.groupNameSaved, "Group name saved"),
            (DoneCopy.groupDeleted, "Group deleted"),
            (DoneCopy.hapticsTitle, "Haptics"),
            (DoneCopy.hapticsSummary, "A tap when it is done"),
        ]
        for (got, want) in table {
            XCTAssertEqual(got, want)
            XCTAssertFalse(got.contains("\u{2014}"), "\(got): an em dash") // em-dash-ok: the test asserts this dash is absent
        }
    }

    /// The spoken form joins with ", " and never reads a separator, even one inside a part.
    func testTheSpokenFormJoinsWithCommas() {
        let line = DoneLine(title: "Expense added", parts: ["Lunch", "₹1,200.00"])
        XCTAssertEqual(DoneCopy.spoken(line), "Expense added, Lunch, ₹1,200.00")
        XCTAssertEqual(DoneCopy.spoken(.small(line)), "Expense added, Lunch, ₹1,200.00")
        XCTAssertEqual(DoneCopy.spoken(.big(BigMoment(kind: .firstGroup, title: "Group created", parts: ["Goa trip", "4 people", "INR"]))),
                       "Group created, Goa trip, 4 people, INR")
        XCTAssertEqual(DoneCopy.spoken(title: "Signed out", parts: []), "Signed out")
        let nested = DoneLine(title: "Expense added", parts: ["Food\(Separator.meta(for: .norse))Lunch", "Taxi\(Separator.meta(for: .aurora))Home"])
        for spoken in [DoneCopy.spoken(line), DoneCopy.spoken(nested)] {
            XCTAssertFalse(spoken.contains(Runes.separator), spoken)
            XCTAssertFalse(spoken.contains("\u{00B7}"), spoken)
            XCTAssertFalse(spoken.contains("\u{2014}"), spoken) // em-dash-ok: the test asserts this dash is absent
        }
        XCTAssertEqual(DoneCopy.spoken(nested), "Expense added, Food, Lunch, Taxi, Home")
    }

    /// The centre holds one moment: a post replaces the one showing (no queue) and records its start; clear removes it.
    /// D-224 fix wave: on a recorder, never the app's gate, haptics or announcements.
    @MainActor
    func testTheCenterReplacesAndClears() {
        let center = Recorder().center()
        XCTAssertNil(center.current)
        let first = DoneMoment.small(DoneLine(title: DoneCopy.expenseAdded, parts: ["Lunch"]))
        let second = DoneMoment.small(DoneLine(title: DoneCopy.nameSaved, parts: []))
        center.post(first, now: 100)
        XCTAssertEqual(center.current, first)
        XCTAssertEqual(center.startedAt, 100)
        let serial = center.serial
        center.post(second, now: 101.5)
        XCTAssertEqual(center.current, second, "replaced at once")
        XCTAssertEqual(center.startedAt, 101.5)
        XCTAssertEqual(center.serial, serial + 1)
        center.post(second, now: 102)
        XCTAssertEqual(center.serial, serial + 2, "the same words posted again are a new moment")
        center.clear()
        XCTAssertNil(center.current)
    }

    /// One door to the haptics: no app file but DoneMoments.swift touches a feedback generator, and `Haptics.play`
    /// reads the Haptics switch.
    func testHapticsHaveOneDoor() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }
        XCTAssertTrue(files.contains("DoneMoments.swift"))
        XCTAssertGreaterThan(files.count, 20, "the app's sources were found")
        let calls = ["UIImpactFeedbackGenerator", "UINotificationFeedbackGenerator", "UISelectionFeedbackGenerator", ".sensoryFeedback("]
        for file in files where file != "DoneMoments.swift" {
            let source = try appSource(file)
            for call in calls { XCTAssertFalse(source.contains(call), "\(file) plays a haptic itself: \(call)") }
        }
        let play = try slice(try appSource("DoneMoments.swift"), from: "static func play(_ kind: HapticKind)", to: "\n    }\n")
        XCTAssertTrue(play.contains("HapticsSetting.storageKey"), "play reads the switch")
        XCTAssertTrue(play.contains("HapticsSetting.parse("), "play reads the switch through its default")
        XCTAssertTrue(play.contains("guard plays(kind, enabled:"), "play asks the decision first")
        XCTAssertTrue(play.contains("UIImpactFeedbackGenerator(style: .light)"))
        XCTAssertTrue(play.contains("notificationOccurred(.success)"))
        XCTAssertTrue(play.contains("notificationOccurred(.warning)"))
    }

    /// D-224 (L48): Lucide's vibrate (lucide-static v1.59.0, read 2026-10-17), in upstream's order: both paths verbatim,
    /// then the `<rect width="8" height="14" x="8" y="5" rx="1">` as SVG 2's equivalent rounded-rect path. Not on Lucide's
    /// Feather-derived list (ISC only).
    func testTheVibrateIsLucidesOwnPath() throws {
        XCTAssertEqual(LucideGlyph.vibrate.pathData, [
            "m2 8 2 2-2 2 2 2-2 2",
            "m22 8-2 2 2 2-2 2 2 2",
            "M9 5 L15 5 A1 1 0 0 1 16 6 L16 18 A1 1 0 0 1 15 19 L9 19 A1 1 0 0 1 8 18 L8 6 A1 1 0 0 1 9 5 Z",
        ])
        XCTAssertEqual(LucideGlyph.vibrate.rawValue, "vibrate")
        // The phone spans the rect (8 to 16 across, 5 to 19 down); the rays reach 2 and 22 across, 8 to 16 down.
        let phone = try SVGPath.parse(LucideGlyph.vibrate.pathData[2]).cgPath.boundingBoxOfPath
        XCTAssertEqual(phone.minX, 8, accuracy: 0.01)
        XCTAssertEqual(phone.maxX, 16, accuracy: 0.01)
        XCTAssertEqual(phone.minY, 5, accuracy: 0.01)
        XCTAssertEqual(phone.maxY, 19, accuracy: 0.01)
        let all = LucideGlyph.vibrate.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(all.minX, 2, accuracy: 0.01)
        XCTAssertEqual(all.maxX, 22, accuracy: 0.01)
        XCTAssertEqual(all.minY, 5, accuracy: 0.01)
        XCTAssertEqual(all.maxY, 19, accuracy: 0.01)
    }

    // MARK: - D-224 Task 3: the views, their hosts, the Haptics row and the tab haptic

    /// Records what a centre plays and says, in place of the phone.
    @MainActor
    private final class Recorder {
        var haptics: [(HapticKind, Double)] = []
        var spoken: [String] = []
        /// D-224 T5: each recorder has its own gate (the app's centres share `SuccessGate.shared`), so the clocks of
        /// two tests never meet; pass one in to share it between recorders.
        let gate: SuccessGate

        init(gate: SuccessGate? = nil) {
            self.gate = gate ?? SuccessGate()
        }

        func center() -> DoneCenter {
            DoneCenter(gate: gate, haptic: { [self] kind, delay in haptics.append((kind, delay)) },
                       announce: { [self] in spoken.append($0) })
        }
    }

    /// views-extra 1: the success haptic is gated exactly once, in `post`: two posts inside the gate window play one
    /// success, and two a window apart play two. The announcement goes once per post, in the spoken form.
    @MainActor
    func testThePostGatesTheSuccessHaptic() {
        let inside = Recorder()
        let a = inside.center()
        a.post(.small(DoneLine(title: DoneCopy.expenseAdded, parts: ["Lunch", "₹1,200.00"])), now: 100, still: false)
        a.post(.small(DoneLine(title: DoneCopy.settledWith("Asha"), parts: ["₹600.00"])), now: 100.5, still: false)
        XCTAssertEqual(inside.haptics.count, 1, "0.5 s apart: one success")
        XCTAssertEqual(inside.haptics.first?.0, .success)
        XCTAssertEqual(inside.haptics.first?.1, 0, "a small moment: at once")
        XCTAssertEqual(inside.spoken, ["Expense added, Lunch, ₹1,200.00", "Settled with Asha, ₹600.00"], "each post said once")
        a.post(.small(DoneLine(title: DoneCopy.nameSaved, parts: [])), now: 101.6, still: false)
        XCTAssertEqual(inside.haptics.count, 2, "1.1 s after the last success: plays")

        let outside = Recorder()
        let b = outside.center()
        b.post(.small(DoneLine(title: DoneCopy.nameSaved, parts: [])), now: 50, still: false)
        b.post(.small(DoneLine(title: DoneCopy.pictureSaved, parts: [])), now: 51, still: false)
        XCTAssertEqual(outside.haptics.map { $0.0 }, [.success, .success], "a window apart: two")
        XCTAssertEqual(b.lastSuccess, 51)
    }

    /// A big moment's haptic waits for its check to land (0.7 s), and the gate is measured then: a small post 0.5 s
    /// after it falls inside the window of the landing, so it plays none. Still, the big one plays at once.
    @MainActor
    func testTheBigHapticWaitsForTheCheck() throws {
        let big = DoneMoment.big(BigMoment(kind: .firstGroup, title: DoneCopy.groupCreated, parts: ["Goa trip", "4 people", "INR"]))
        let moving = Recorder()
        let a = moving.center()
        a.post(big, now: 10, still: false)
        XCTAssertEqual(moving.haptics.count, 1)
        XCTAssertEqual(moving.haptics.first?.1, CheckMotion.popAt, "when the check lands")
        XCTAssertEqual(try XCTUnwrap(a.lastSuccess), 10.7, accuracy: 1e-9)
        a.post(.small(DoneLine(title: DoneCopy.nameSaved, parts: [])), now: 10.5, still: false)
        XCTAssertEqual(moving.haptics.count, 1, "inside the landing's window: none")
        XCTAssertEqual(moving.spoken, ["Group created, Goa trip, 4 people, INR", "Name saved"])

        let still = Recorder()
        still.center().post(big, now: 10, still: true)
        XCTAssertEqual(still.haptics.first?.1, 0, "still: at once")
        // A screen's check calls `success` directly, through the same gate.
        let screen = Recorder()
        let c = screen.center()
        c.success(now: 20)
        c.success(now: 20.4)
        c.success(now: 21.4)
        XCTAssertEqual(screen.haptics.count, 2)
    }

    /// A host ends only the moment it started with: a newer post is left alone. The time left never goes below 0.
    @MainActor
    func testTheHostEndsOnlyItsOwnMoment() {
        let center = Recorder().center()
        center.post(.small(DoneLine(title: DoneCopy.nameSaved, parts: [])), now: 0, still: false)
        let first = center.serial
        center.post(.small(DoneLine(title: DoneCopy.pictureSaved, parts: [])), now: 1, still: false)
        center.clear(ifStill: first)
        XCTAssertEqual(center.current, .small(DoneLine(title: DoneCopy.pictureSaved, parts: [])), "the newer moment stays")
        center.clear(ifStill: center.serial)
        XCTAssertNil(center.current)
        XCTAssertEqual(DoneCenter.remaining(total: 3, startedAt: 100, now: 101.25), 1.75)
        XCTAssertEqual(DoneCenter.remaining(total: 3, startedAt: 100, now: 100), 3)
        XCTAssertEqual(DoneCenter.remaining(total: 3, startedAt: 100, now: 104), 0, "never below 0")
        XCTAssertTrue(DoneMoment.big(BigMoment(kind: .signedIn, title: "", parts: [])).isBig)
        XCTAssertFalse(DoneMoment.small(DoneLine(title: "", parts: [])).isBig)
    }

    /// The tick is drawn through its three points, scaled from the 104 box: at 208 every point doubles.
    func testTheTickPathIsTheArtTable() {
        let path = DoneCheck.tickPath(side: 208).cgPath
        let box = path.boundingBoxOfPath
        XCTAssertEqual(box.minX, 64, accuracy: 1e-9)
        XCTAssertEqual(box.maxX, 146, accuracy: 1e-9)
        XCTAssertEqual(box.minY, 78, accuracy: 1e-9)
        XCTAssertEqual(box.maxY, 134, accuracy: 1e-9)
        XCTAssertEqual(path.currentPoint, CGPoint(x: 146, y: 78), "it ends on the third point")
        let unit = DoneCheck.tickPath(side: 104).cgPath.boundingBoxOfPath
        XCTAssertEqual(unit, CGRect(x: 32, y: 39, width: 41, height: 28))
    }

    /// Turning the switch on plays one success; turning it off plays nothing.
    func testTheHapticsRowFeedback() {
        XCTAssertEqual(HapticsRow.feedback(turnedOn: true), .success)
        XCTAssertNil(HapticsRow.feedback(turnedOn: false))
    }

    /// A struct's text in `source`: from its declaration to the first line that closes it at column 0.
    private func declaration(_ source: String, _ head: String) throws -> String {
        XCTAssertEqual(source.components(separatedBy: head).count, 2, "exactly one \(head)")
        return try slice(source, from: head, to: "\n}\n")
    }

    /// GUARANTEES #2: the toast, its host and the overlay never take a touch, and the file presents nothing and holds no
    /// control (nothing can wait on a person).
    func testTheMomentsNeverTakeATouch() throws {
        let source = try appSource("DoneMoments.swift")
        for head in ["struct DoneToast: View {", "struct DoneToastHost: View {", "struct DoneOverlay: View {"] {
            XCTAssertTrue(try declaration(source, head).contains(".allowsHitTesting(false)"), "\(head) takes touches")
        }
        for banned in [".sheet(", ".fullScreenCover(", ".alert(", ".confirmationDialog(", "Button(", ".onTapGesture", ".gesture("] {
            XCTAssertFalse(source.contains(banned), "DoneMoments.swift holds \(banned)")
        }
    }

    /// Every motion is the engine's: the shared 30 fps grid, the pure poses on the moment's clock, the still rule; no
    /// SwiftUI animation of any kind.
    func testTheMotionIsTheEngines() throws {
        let source = try appSource("DoneMoments.swift")
        XCTAssertTrue(source.contains("ArtSchedule(") || source.contains("ArtCanvas("), "drawn on the art's grid")
        for read in ["CheckMotion.pose(", "ToastMotion.pose(", "MomentClock.elapsed(", "MotionClock.momentTicks(",
                     "CelebrationTimeline.dim(", "CelebrationTimeline.message("] {
            XCTAssertTrue(source.contains(read), "DoneMoments.swift reads \(read)")
        }
        // `withAnimation` in any form: the trailing-closure call has no bracket (sabotage H2 found the gap). D-224 fix wave
        // (M3): closed over the whole family, read in the code with its comments taken out and in any case: every
        // animation (`withAnimation`, `.animation(`, `Animation.`, `phaseAnimator`, `keyframeAnimator`, `UIView.animate`,
        // `CABasicAnimation`), every transaction (`withTransaction`, `.transaction`), every transition (`.transition(`,
        // `.contentTransition(`), `matchedGeometryEffect`, `repeatForever`.
        let code = source.components(separatedBy: "\n").map { line in
            line.range(of: "//").map { String(line[..<$0.lowerBound]) } ?? line
        }.joined(separator: "\n").lowercased()
        for banned in ["animat", "transaction", "transition", "matchedgeometry", "keyframe", "repeatforever"] {
            XCTAssertFalse(code.contains(banned), "DoneMoments.swift animates by itself: \(banned)")
        }
    }

    /// The check is drawn from the art table: its sizes, the tick's points, accent on accentSoft (Norse's dark gold well),
    /// E4's rune ring only in Norse; decorative.
    func testTheCheckIsTheArtTable() throws {
        let check = try declaration(try appSource("DoneMoments.swift"), "struct DoneCheck: View {")
        for read in ["ThemeArt.m(\"doneCheck\")", "\"doneCircleRadius\"", "\"doneCircleStroke\"", "\"doneTickStroke\"",
                     "\"ringBox\"", "DoneCheck.tick", "Tokens.accent", "Tokens.accentSoft",
                     "ThemeArt.color(\"norseCheckFill\")", "CheckMotion.pose(", ".accessibilityHidden(true)",
                     "RuneRing(done: 1, total: 1)"] {
            XCTAssertTrue(check.contains(read), "DoneCheck reads \(read)")
        }
        XCTAssertEqual(check.components(separatedBy: "RuneRing(").count, 2, "one rune ring")
        let before = try XCTUnwrap(check.components(separatedBy: "RuneRing(").first)
        let condition = try XCTUnwrap(before.components(separatedBy: "if ").last)
        XCTAssertTrue(condition.hasPrefix("theme == .norse {"), "the ring only under a Norse condition: \(condition.prefix(40))")
        XCTAssertFalse(check.contains("Tokens.surface"), "the well is accentSoft, not the surface")
    }

    /// The toast is drawn from the art table: the panel shape at its radius, the owing dot with Lucide's check, the
    /// surface with its hairline, the toast's type and the theme's separator.
    func testTheToastIsTheArtTable() throws {
        let toast = try declaration(try appSource("DoneMoments.swift"), "struct DoneToast: View {")
        for read in ["ThemeArt.panelShape(", "\"toastRadius\"", "\"toastDot\"", "\"toastGlyph\"", "\"toastCheckStroke\"",
                     "\"toastGap\"", "\"toastPadV\"", "\"toastPadH\"", "\"toastInset\"", "\"toastShadowAlpha\"",
                     "\"toastShadowRadius\"", "\"toastShadowY\"", "Tokens.owing", "Tokens.onAccent", "Tokens.surface",
                     "Tokens.hairline", "Tokens.text", "LucideIcon(glyph: .check", "TypeRole.name(TypeScale.toast)",
                     "Separator.text(", ".lineLimit(2)"] {
            XCTAssertTrue(toast.contains(read), "DoneToast reads \(read)")
        }
        let host = try declaration(try appSource("DoneMoments.swift"), "struct DoneToastHost: View {")
        XCTAssertTrue(host.contains("ToastPlace.bottom(barShown: barShown)"), "the host places it")
    }

    /// The shell dims its tab content, never the bar or the +: the dim and the overlay come before the bar's overlay.
    /// The light haptic plays on a tab SWITCH only.
    func testTheShellNeverDimsTheBar() throws {
        let shell = try appSource("HomeShell.swift")
        let body = try slice(shell, from: "    var body: some View {", to: "    private func tapped(")
        let bar = try XCTUnwrap(body.range(of: ".overlay(alignment: .bottom) {\n            if chrome.showsBar"), "the bar's overlay")
        XCTAssertTrue(body[bar.upperBound...].prefix(160).contains("SkiptiTabBar("), "that overlay holds the bar")
        for host in [".modifier(DoneDim(center: DoneCenter.shared))", "DoneOverlay(center: DoneCenter.shared)"] {
            let at = try XCTUnwrap(body.range(of: host), "\(host) in the shell")
            XCTAssertLessThan(at.lowerBound, bar.lowerBound, "\(host) comes before the bar's overlay")
            XCTAssertEqual(body.components(separatedBy: host).count, 2, "one \(host)")
        }
        let toast = try XCTUnwrap(body.range(of: "DoneToastHost(center: DoneCenter.shared, barShown: chrome.showsBar(on: selection.wrappedValue))"))
        XCTAssertGreaterThan(toast.lowerBound, bar.lowerBound, "the toast is not dimmed either")
        let tapped = try slice(shell, from: "    private func tapped(", to: "\n    }\n")
        let switchTo = try slice(tapped, from: "case .switchTo", to: "case .popToRoot")
        let popToRoot = try slice(tapped, from: "case .popToRoot", to: "case .scrollToTop")
        let scrollToTop = try XCTUnwrap(tapped.components(separatedBy: "case .scrollToTop").last)
        XCTAssertTrue(switchTo.contains("Haptics.play(.light)"), "a switch plays the light haptic")
        XCTAssertFalse(popToRoot.contains("Haptics.play"), "a re-tap to the root plays none")
        XCTAssertFalse(scrollToTop.contains("Haptics.play"), "a re-tap to the top plays none")
        XCTAssertEqual(shell.components(separatedBy: "Haptics.play(").count, 2, "the shell plays one haptic, in one place")
    }

    /// The signed-in screen draws the done check, not the system symbol.
    func testTheSignedInScreenDrawsTheCheck() throws {
        let screen = try declaration(try appSource("AccountOwner.swift"), "struct SignedInConfirmation: View {")
        XCTAssertTrue(screen.contains("DoneCheck(.screen)"))
        XCTAssertFalse(screen.contains("checkmark.circle.fill"))
        XCTAssertFalse(screen.contains("Image(systemName:"))
    }

    /// The Haptics row sits on Appearance after the Text size card: the vibrate tile, the stored switch, a `.toggle` row
    /// (D-225: the drawn `ThemeSwitch`, its Toggle only the row's accessibility representation); the success haptic only
    /// when it is turned on.
    func testTheHapticsRowIsOnAppearance() throws {
        let hub = try appSource("AccountHubScreen.swift")
        let textSize = try slice(hub, from: "        case .textSize:", to: "        case .appLock:")
        let slider = try XCTUnwrap(textSize.range(of: "TextSizeSlider("))
        let row = try XCTUnwrap(textSize.range(of: "HapticsRow()"), "the row on Appearance")
        XCTAssertGreaterThan(row.lowerBound, slider.upperBound, "after the Text size card")
        let haptics = try declaration(hub, "struct HapticsRow: View {")
        for read in ["ListCard {", "ListRow(glyph: .vibrate, title: DoneCopy.hapticsTitle, parts: [DoneCopy.hapticsSummary], trailing: .toggle(isOn))",
                     "@AppStorage(HapticsSetting.storageKey)", "HapticsRow.feedback(turnedOn:"] {
            XCTAssertTrue(haptics.contains(read), "HapticsRow reads \(read)")
        }
        XCTAssertFalse(haptics.contains("Toggle("), "no system switch on the row")
        // D-225: the `.toggle` row draws the drawn switch (ListRows.swift).
        let listRow = try declaration(try appSource("ListRows.swift"), "struct ListRow: View {")
        XCTAssertTrue(listRow.contains("ThemeSwitch(isOn: isOn.wrappedValue, enabled: enabled)"), "the .toggle row draws ThemeSwitch")
        // D-224 fix wave (M1): the row's success goes through the app's one gate, as Android's does.
        XCTAssertEqual(haptics.components(separatedBy: "DoneCenter.shared.success()").count, 2, "one success, through the gate")
        XCTAssertFalse(haptics.contains("Haptics.play("), "the row never plays past the gate")
    }

    // MARK: - D-224 Task 5: the call sites (Android's twins are DoneMomentsTest's Task 6 guards)

    /// Every Swift file of the app, by name.
    private func appFiles() throws -> [String] {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        return try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }.sorted()
    }

    /// The text between the brackets of the call whose `(` ends `match` in `source`.
    private func arguments(_ source: String, after match: Range<String.Index>) -> String {
        var depth = 1
        var i = match.upperBound
        while i < source.endIndex, depth > 0 {
            if source[i] == "(" { depth += 1 }
            if source[i] == ")" { depth -= 1 }
            if depth > 0 { i = source.index(after: i) }
        }
        return String(source[match.upperBound..<i])
    }

    /// Every call's arguments for `pattern` (a regular expression ending in `\(`) in `source`.
    private func calls(_ pattern: String, in source: String) -> [String] {
        var out: [String] = []
        var from = source.startIndex
        while let hit = source.range(of: pattern, options: .regularExpression, range: from..<source.endIndex) {
            out.append(arguments(source, after: hit))
            from = hit.upperBound
        }
        return out
    }

    /// Every post to a centre: the app's (`DoneCenter.shared`) and the add-person sheet's own (`localDone`).
    private let postCall = #"(DoneCenter\.shared|localDone)\.post\("#

    /// Design 7, 8: per file, the number of moments posted and the words each file's posts name, so a lost site fails by
    /// name; every post goes through `DoneMoments` and reads `DoneCopy` (or the add-person sheet's kept words), never a
    /// string literal; no screen builds a moment of its own.
    func testEverySmallMomentIsPosted() throws {
        let table: [String: (Int, [String])] = [
            "AddExpenseScreen.swift": (2, ["DoneMoments.expense(edited: false", "DoneMoments.expense(edited: true"]),
            "GroupSheets.swift": (1, ["DoneMoments.groupCreated("]),
            "People.swift": (1, ["AddPersonCopy.done(who, invited: invited)"]),
            "GroupPicker.swift": (1, ["DoneCopy.personRemoved(member.name)"]),
            "RemovalFlow.swift": (1, ["DoneCopy.personRemoved("]),
            "GroupSettings.swift": (4, ["DoneCopy.groupNameSaved", "DoneCopy.groupPictureRemoved", "DoneCopy.groupPictureSaved",
                                        "DoneCopy.groupDeleted"]),
            "OverviewScreen.swift": (1, ["DoneCopy.groupPictureRemoved", "DoneCopy.groupPictureSaved"]),
            "Profile.swift": (2, ["DoneCopy.nameSaved", "DoneCopy.pictureRemoved", "DoneCopy.pictureSaved"]),
            "UsernameStep.swift": (2, ["DoneCopy.usernameSaved, PeopleCopy.handle(h)", "DoneCopy.usernameSaved, PeopleCopy.handle(saved)"]),
            "AccountHubScreen.swift": (1, ["DoneCopy.signedOut"]),
            "SettleAll.swift": (1, ["DoneCopy.settledWith("]),
        ]
        var found: [String: Int] = [:]
        for file in try appFiles() where file != "DoneMoments.swift" {
            let source = try appSource(file)
            for word in ["DoneLine(", "BigMoment(", "SuccessGate("] {
                XCTAssertFalse(source.contains(word), "\(file) builds a moment or a gate itself (\(word))")
            }
            let posts = calls(postCall, in: source)
            if posts.isEmpty { continue }
            found[file] = posts.count
            for arg in posts {
                XCTAssertTrue(arg.hasPrefix("DoneMoments."), "\(file): the post goes through DoneMoments: \(arg)")
                XCTAssertFalse(arg.contains("\""), "\(file): no string literal in a post: \(arg)")
                XCTAssertTrue(["DoneCopy.", "DoneMoments.expense(", "DoneMoments.groupCreated(", "AddPersonCopy.done("]
                    .contains { arg.contains($0) }, "\(file): the post reads DoneCopy: \(arg)")
            }
            let words = posts.joined(separator: "\n")
            for want in table[file]?.1 ?? [] { XCTAssertTrue(words.contains(want), "\(file) posts \(want)") }
        }
        XCTAssertEqual(found, table.mapValues(\.0), "the posting files and their counts")
        // The add-person sheet's own centre and host (it is a sheet above the shell's host); its old done bar is gone.
        let people = try appSource("People.swift")
        XCTAssertTrue(people.contains("@State private var localDone = DoneCenter()"), "a local centre, on the app's gate")
        XCTAssertTrue(people.contains("DoneToastHost(center: localDone)"), "its own host, no bar")
        XCTAssertFalse(people.contains("@State private var done: String?"), "the done bar is gone")
        XCTAssertFalse(people.contains("checkmark.circle.fill"), "the done bar's symbol is gone")
        // The settle post is in the one record function, so Settle up and the group's next card both say it.
        let record = try slice(try appSource("SettleAll.swift"), from: "static func record(store: LedgerStore,", to: "\n    }\n")
        XCTAssertTrue(record.contains("DoneCenter.shared.post(DoneMoments.line(DoneCopy.settledWith("), "the shared record posts")
        XCTAssertTrue(record.contains("Task { @MainActor in"), "on the main actor")
        XCTAssertTrue(record.contains("DoneMoments.amount("), "its amount is core's")
        XCTAssertTrue(record.contains("historyNames(group:"), "a removed person keeps their name")
        // A removal says so when it is done; a request's decline removes nobody.
        let removal = try appSource("RemovalFlow.swift")
        XCTAssertTrue(try slice(removal, from: "    func perform(redistributions:", to: "\n    }\n")
            .contains("DoneCenter.shared.post(DoneMoments.line(DoneCopy.personRemoved(member.name)))"))
        XCTAssertFalse(try slice(removal, from: "    private func decline() {", to: "\n    }\n").contains("DoneCenter"),
                       "a decline posts nothing")
        // The username step posts only after a real save, outside the sign-in flow (inside it the signed-in screen follows).
        let step = try declaration(try appSource("UsernameStep.swift"), "struct UsernameStep: View {")
        let firstSave = try slice(step, from: "_ = try await People.setUsername(h, first: true)", to: "onDone()")
        XCTAssertTrue(firstSave.contains("if onCancel != nil { DoneCenter.shared.post("), "outside sign-in, after the save")
        XCTAssertEqual(step.components(separatedBy: "DoneCenter").count, 2, "the step's other ways out post nothing")
    }

    /// Design 7: a moment's line drops a missing or blank part; an expense reads its description and core's amount.
    @MainActor
    func testALineHoldsOnlyThePartsThatAreThere() throws {
        XCTAssertEqual(DoneMoments.line(DoneCopy.nameSaved, nil, "", "  "), .small(DoneLine(title: DoneCopy.nameSaved, parts: [])))
        XCTAssertEqual(DoneMoments.line(DoneCopy.usernameSaved, " @asha "),
                       .small(DoneLine(title: DoneCopy.usernameSaved, parts: ["@asha"])))
        let amount = try ffiMoneyFormat(minorUnits: 240_000, currencyCode: "INR", locale: AppLocale.current)
        XCTAssertEqual(DoneMoments.amount(240_000, "INR"), amount)
        XCTAssertEqual(DoneMoments.expense(edited: false, note: "Dinner", paidMinor: 240_000, currency: "INR"),
                       .small(DoneLine(title: "Expense added", parts: ["Dinner", amount])))
        XCTAssertEqual(DoneMoments.expense(edited: true, note: "", paidMinor: 240_000, currency: "INR"),
                       .small(DoneLine(title: "Expense saved", parts: [amount])), "a blank note is left out")
        XCTAssertNil(DoneMoments.amount(1, "ZZZ"), "core refuses a made-up currency: no part")
    }

    /// Design 8, GUARANTEES #2: Add closes at once. No "Saved" label and no wait: each success branch posts the moment,
    /// then closes.
    func testAddExpenseClosesAtOnce() throws {
        let source = try appSource("AddExpenseScreen.swift")
        XCTAssertFalse(source.contains("asyncAfter"), "nothing holds the sheet open")
        XCTAssertFalse(source.contains("\"Saved\""), "no Saved label")
        for (from, edited) in [("    private func save() {", "false"), ("    private func saveEdit(", "true")] {
            let branch = try slice(source, from: from, to: "\n    }\n")
            let post = try XCTUnwrap(branch.range(of: "DoneCenter.shared.post(DoneMoments.expense(edited: \(edited)"), from)
            let close = try XCTUnwrap(branch.range(of: "if let onSaved { onSaved() } else { dismiss() }"), from)
            XCTAssertLessThan(post.lowerBound, close.lowerBound, "\(from): the post, then the close")
            let between = branch[post.upperBound..<close.lowerBound]
            for wait in ["await", "sleep", "Task", "asyncAfter", "withAnimation"] {
                XCTAssertFalse(between.contains(wait), "\(from): nothing waits between the post and the close (\(wait))")
            }
        }
    }

    /// The files that warn and how many confirmations each warns for (Design 3). Android's table has 9 files: it has no
    /// VaultRecoveryView twin (its vault gate asks nothing), and GroupPicker's removal lives in its GroupScreens.kt.
    private let warnings: [String: Int] = [
        "ExpenseListView.swift": 1, // delete this expense (the row's dialog)
        "ExpenseDetailView.swift": 1, // delete this expense
        "GroupPicker.swift": 1, // remove a person from a local group
        "GroupSettings.swift": 3, // delete for everyone, delete here, forget a copy
        "AccountOwner.swift": 1, // erase this phone's ledger
        "VaultRecoveryView.swift": 1, // erase a vault that will not open, once asked
        "Phones.swift": 3, // sign a phone out, sign out everywhere, delete the account
        "AppLock.swift": 1, // turn the lock off
        "UsernameStep.swift": 1, // the one username change
        "RemovalFlow.swift": 4, // make owner; the three red-button removal screens
    ]

    /// Design 3: per file, the count of `.hapticWarning(`; every file with a destructive control (a `role: .destructive`
    /// button, in a dialog or one that opens it, or a red primary action) is in the table; the warning plays only through
    /// the modifier, as the confirmation opens, never on its final tap.
    func testEveryDestructiveConfirmationWarns() throws {
        var found: [String: Int] = [:]
        for file in try appFiles() where file != "DoneMoments.swift" {
            let source = try appSource(file)
            let n = source.components(separatedBy: ".hapticWarning(when: ").count - 1
            if n > 0 { found[file] = n }
            XCTAssertFalse(source.contains(".warning)"), "\(file) plays a warning past the modifier")
            if source.contains("role: .destructive") || source.contains("PrimaryButtonStyle(tint: Tokens.owed)") {
                XCTAssertNotNil(warnings[file], "\(file) confirms something destructive and is not in the table")
            }
        }
        XCTAssertEqual(found, warnings, "the warning sites")
        let removal = try appSource("RemovalFlow.swift")
        for screen in ["PlainRemoveView", "SettleFirstView", "RedistributeView"] {
            XCTAssertTrue(try declaration(removal, "private struct \(screen): View {").contains(".hapticWarning(when: true)"),
                          "\(screen) warns when it appears")
        }
        XCTAssertTrue(removal.contains(".hapticWarning(when: confirm == .makeOwner)"), "the role question warns for the owner's transfer only")
        let modifier = try declaration(try appSource("DoneMoments.swift"), "struct HapticWarning: ViewModifier {")
        XCTAssertTrue(modifier.contains(".onChange(of: show, initial: true)"), "it plays as the flag turns true, or on appearing")
        XCTAssertTrue(modifier.contains("WarningLatch.next(show:"), "through the latch")
        XCTAssertTrue(modifier.contains("Haptics.play(.warning)"), "the warning kind")
    }

    /// Design 3: the latch plays once per opening, not again while the same one stays open, and re-arms on close.
    func testTheWarningPlaysOncePerOpening() {
        let table: [(show: Bool, played: Bool, play: Bool, after: Bool, why: String)] = [
            (false, false, false, false, "closed: nothing"),
            (true, false, true, true, "opens: plays"),
            (true, true, false, true, "still open: nothing"),
            (false, true, false, false, "closes: re-armed"),
        ]
        for row in table {
            let step = WarningLatch.next(show: row.show, played: row.played)
            XCTAssertEqual(step.play, row.play, row.why)
            XCTAssertEqual(step.played, row.after, row.why)
        }
    }

    /// Design 7, 8: the joined page and the erased screen draw the big check; the erase's alert is gone from the root,
    /// replaced by its cover with "OK"; the first group is read after the create (never big without proof).
    func testTheBigMomentsAreTheCheck() throws {
        let joined = try slice(try appSource("Invites.swift"), from: "case .joined(let group, let name):", to: "case .failed")
        let check = try XCTUnwrap(joined.range(of: "DoneCheck(.screen)"), "the joined page's check")
        XCTAssertLessThan(check.lowerBound, try XCTUnwrap(joined.range(of: "InviteCopy.joined(")).lowerBound, "above its heading")
        XCTAssertTrue(joined.contains(".frame(maxWidth: .infinity)"), "centred")
        let erased = try declaration(try appSource("AccountOwner.swift"), "struct ErasedScreen: View {")
        for want in ["DoneCheck(.screen)", "EraseCopy.doneTitle", "EraseCopy.doneBody", "EraseCopy.doneOK"] {
            XCTAssertTrue(erased.contains(want), "the erased screen has \(want)")
        }
        let app = try appSource("SkiptiApp.swift")
        XCTAssertFalse(app.contains(".alert(EraseCopy.doneTitle"), "no erase alert at the root")
        XCTAssertTrue(app.contains(".fullScreenCover(isPresented: $erasedNotice) {\n                ErasedScreen { erasedNotice = false }"),
                      "the erased screen covers the root, closed by OK")
        let create = try slice(try appSource("GroupSheets.swift"), from: "    private func create() {", to: "\n    }\n")
        let made = try XCTUnwrap(create.range(of: "try CreateGroup.create("))
        let count = try XCTUnwrap(create.range(of: "let namedCount = (try? store.namedGroups().count) ?? 0"), "the count, read")
        XCTAssertLessThan(made.lowerBound, count.lowerBound, "after the create")
    }

    /// Design 9: E4's celebration, when it starts, clears a toast and plays the success through the app's gate; the
    /// milestone card plays nothing.
    func testTheCelebrationClearsAndBuzzes() throws {
        let settled = try appSource("Settled.swift")
        let start = try slice(try declaration(settled, "struct CelebrationOverlay: View {"), from: ".onAppear {", to: "}")
        XCTAssertTrue(start.contains("DoneCenter.shared.clear()"), "clears the toast")
        XCTAssertTrue(start.contains("DoneCenter.shared.success()"), "plays the success, through the gate")
        XCTAssertEqual(settled.components(separatedBy: "DoneCenter.shared.success()").count, 2, "the celebration's haptic only")
        XCTAssertFalse(settled.contains("Haptics."), "never past the gate")
        let milestone = try declaration(settled, "struct MilestoneCard: View {")
        XCTAssertFalse(milestone.contains("DoneCenter") || milestone.contains("Haptics"), "the milestone card plays nothing")
    }

    /// Design 7: export and import show their own result and add only the success, through the app's gate.
    func testExportAndImportBuzz() throws {
        for file in ["ExportFlow.swift", "ImportReview.swift"] {
            let source = try appSource(file)
            XCTAssertEqual(source.components(separatedBy: "DoneCenter.shared.success()").count, 2, "\(file) buzzes once")
            XCTAssertFalse(source.contains(".post("), "\(file) posts no toast")
            XCTAssertFalse(source.contains("Haptics."), "\(file): never past the gate")
        }
    }

    /// sites-extra 9: one success gate for the app, whichever centre posts. Two centres on one gate, two successes
    /// inside the window: one plays. Every centre made without a gate (the add-person sheet's) uses the app's, as the
    /// shell's does, and no success plays past it.
    @MainActor
    func testEverySuccessSharesOneGate() throws {
        let gate = SuccessGate()
        let shell = Recorder(gate: gate), sheet = Recorder(gate: gate)
        sheet.center().post(.small(DoneLine(title: DoneCopy.expenseAdded, parts: [])), now: 200, still: false)
        shell.center().post(.small(DoneLine(title: DoneCopy.settledWith("Asha"), parts: [])), now: 200.5, still: false)
        XCTAssertEqual(sheet.haptics.count + shell.haptics.count, 1, "two centres, 0.5 s apart: one success")
        XCTAssertEqual(gate.lastSuccess, 200)
        shell.center().success(now: 201.2)
        XCTAssertEqual(shell.haptics.count, 1, "1.2 s after: plays")
        XCTAssertTrue(DoneCenter().gate === SuccessGate.shared, "a centre's default gate is the app's")
        XCTAssertTrue(DoneCenter.shared.gate === SuccessGate.shared, "the shell's too")
        for file in try appFiles() where file != "DoneMoments.swift" {
            let source = try appSource(file)
            XCTAssertFalse(source.contains("Haptics.play(.success)"), "\(file) plays a success past the gate")
            XCTAssertFalse(source.contains("DoneCenter(gate:"), "\(file) gives a centre its own gate")
        }
        // D-224 fix wave (M1): every direct `Haptics.play(` in the app, by file and argument. Only the allowed doors: the
        // centre's `playHaptic` (twice, its `kind`), the warning modifier, the shell's tab switch. A success passed as a
        // variable (the Haptics row's old `Haptics.play(kind)`) fails here by name.
        var doors: [String: [String]] = [:]
        for file in try appFiles() {
            let plays = calls(#"Haptics\.play\("#, in: try appSource(file))
            if !plays.isEmpty { doors[file] = plays.sorted() }
        }
        XCTAssertEqual(doors, ["DoneMoments.swift": [".warning", "kind", "kind"], "HomeShell.swift": [".light"]],
                       "the only direct haptics")
    }

    // MARK: - D-224 fix wave (iOS): F1, F3, F4, F9, M2, M4 (D24)

    private let firstGroup = DoneMoment.big(BigMoment(kind: .firstGroup, title: DoneCopy.groupCreated, parts: ["Goa trip"]))

    /// F1 and D24: a big moment posted from a sheet that is closing waits for it (`held`): nothing draws and nothing buzzes
    /// until `begin`, which starts its clock then, so the check is seen drawing from its first frame and its success plays
    /// only when it shows (when the check lands, 0.7 s later). A second `begin` does nothing; a moment replaced before it
    /// began never buzzes; only a big moment waits.
    @MainActor
    func testABigMomentFromASheetWaitsForItToClose() throws {
        let rec = Recorder()
        let center = rec.center()
        center.post(firstGroup, now: 10, still: false, held: true)
        XCTAssertEqual(center.current, firstGroup)
        XCTAssertTrue(center.held, "waits for the sheet")
        XCTAssertFalse(center.showsBig, "nothing drawn under the sheet")
        XCTAssertTrue(rec.haptics.isEmpty, "no success while it cannot be seen (D24)")
        // D-224 follow-up (M1): said when it begins, not while its sheet is closing (VoiceOver can cut words queued
        // as a sheet goes; Android speaks as its overlay composes, after the route has gone).
        XCTAssertTrue(rec.spoken.isEmpty, "not said while it waits")
        center.begin(now: 11.2, still: false)
        XCTAssertEqual(rec.spoken, ["Group created, Goa trip"], "said as it begins")
        XCTAssertFalse(center.held)
        XCTAssertTrue(center.showsBig)
        XCTAssertEqual(center.startedAt, 11.2, "the clock starts as the sheet has gone")
        XCTAssertEqual(rec.haptics.count, 1)
        XCTAssertEqual(rec.haptics.first?.0, .success)
        XCTAssertEqual(rec.haptics.first?.1, CheckMotion.popAt, "when the check lands")
        XCTAssertEqual(try XCTUnwrap(center.lastSuccess), 11.9, accuracy: 1e-9)
        center.begin(now: 13, still: false)
        XCTAssertEqual(center.startedAt, 11.2, "a second begin does nothing")
        XCTAssertEqual(rec.haptics.count, 1)
        XCTAssertEqual(rec.spoken.count, 1, "said once")

        let replaced = Recorder()
        let b = replaced.center()
        b.post(firstGroup, now: 20, still: false, held: true)
        let waiting = b.serial
        b.post(firstGroup, now: 20.1, still: false, held: true)
        b.begin(now: 20.5, still: false, ifStill: waiting)
        XCTAssertTrue(b.held, "an older moment's begin leaves the newer one waiting")
        b.post(.small(DoneLine(title: DoneCopy.nameSaved, parts: [])), now: 20.2, still: false)
        b.begin(now: 21, still: false)
        XCTAssertEqual(replaced.haptics.map { $0.1 }, [0], "only the toast buzzed: the replaced big moment never did")
        XCTAssertEqual(replaced.spoken, ["Name saved"], "a moment replaced before it began is never said")

        let small = Recorder()
        let c = small.center()
        c.post(.small(DoneLine(title: DoneCopy.groupCreated, parts: ["Flat 4B"])), now: 30, still: false, held: true)
        XCTAssertFalse(c.held, "a toast never waits")
        XCTAssertEqual(small.haptics.count, 1)
        XCTAssertEqual(small.spoken, ["Group created, Flat 4B"], "a toast is said at once")

        // The site: the create sheet posts held and begins the moment as it disappears; the overlay draws only a moment
        // that does not wait, and begins one that waits too long (the sheet's disappearance is the start, the limit only
        // a floor under it).
        let sheet = try appSource("GroupSheets.swift")
        let create = try slice(sheet, from: "    private func create() {", to: "\n    }\n")
        let post = try XCTUnwrap(calls(#"DoneCenter\.shared\.post\("#, in: create).first, "the create's post")
        XCTAssertTrue(post.hasPrefix("DoneMoments.groupCreated(") && post.hasSuffix("held: true"), "the create posts held: \(post)")
        let body = try slice(try declaration(sheet, "struct CreateGroupSheet: View {"), from: "    var body: some View {",
                             to: "    private var canCreate: Bool {")
        XCTAssertEqual(body.components(separatedBy: ".onDisappear { DoneCenter.shared.begin() }").count, 2,
                       "the sheet begins the moment as it goes")
        let overlay = try declaration(try appSource("DoneMoments.swift"), "struct DoneOverlay: View {")
        XCTAssertTrue(overlay.contains("if case .big(let moment) = center.current, !center.held"), "drawn only once begun")
        XCTAssertTrue(overlay.contains(".task(id: center.key)"), "its task follows the begin")
        XCTAssertTrue(overlay.contains("center.begin(ifStill: serial)"), "a floor under a wait that never ends")
        XCTAssertGreaterThanOrEqual(DoneCenter.holdLimit, 1.5, "longer than a sheet's close")
    }

    /// F4: a big moment that replaces one still showing holds the page at `dimTo` through its own fade in, rather than
    /// flashing the page bright and dimming again; later its dim is E4's timeline (and it still comes back up at the end).
    /// A big moment over nothing big starts from 1 as before.
    @MainActor
    func testAReplacingBigMomentHoldsTheDim() throws {
        let to = CelebrationTiming.dimTo
        XCTAssertEqual(DoneDimLevel.opacity(elapsed: 0, still: false, holds: true), to)
        XCTAssertEqual(DoneDimLevel.opacity(elapsed: -0.02, still: false, holds: true), to, "a frame before its start")
        XCTAssertEqual(DoneDimLevel.opacity(elapsed: 0.15, still: false, holds: true), to)
        XCTAssertEqual(DoneDimLevel.opacity(elapsed: 0, still: false, holds: false), 1, "over nothing: from 1")
        for elapsed in [0.3, 1.0, 3.45, 3.6] {
            XCTAssertEqual(DoneDimLevel.opacity(elapsed: elapsed, still: false, holds: true),
                           CelebrationTimeline.dim(elapsed: elapsed, still: false), accuracy: 1e-12, "\(elapsed): E4's timeline")
        }
        XCTAssertGreaterThan(DoneDimLevel.opacity(elapsed: 3.5, still: false, holds: true), to, "it still comes back up")
        XCTAssertEqual(DoneDimLevel.opacity(elapsed: 0, still: true, holds: false), to, "still: dimmed at once")

        let center = Recorder().center()
        center.post(firstGroup, now: 0, still: false)
        XCTAssertFalse(center.dimHolds, "the first big moment dims from 1")
        center.post(firstGroup, now: 1, still: false)
        XCTAssertTrue(center.dimHolds, "a big moment replacing one showing holds the dim")
        center.post(.small(DoneLine(title: DoneCopy.nameSaved, parts: [])), now: 2, still: false)
        XCTAssertFalse(center.dimHolds)
        center.post(firstGroup, now: 3, still: false)
        XCTAssertFalse(center.dimHolds, "after a toast nothing was dimmed")
        center.clear()
        XCTAssertFalse(center.dimHolds)
        let dim = try declaration(try appSource("DoneMoments.swift"), "struct DoneDim: ViewModifier {")
        XCTAssertTrue(dim.contains("DoneDimLevel.opacity("), "the dim reads the hold")
        XCTAssertTrue(dim.contains("holds: center.dimHolds"))
        XCTAssertTrue(dim.contains("center.showsBig"), "a waiting moment dims nothing")
    }

    /// M2 and F3: the check moves until the pop of ITS moment is over: a new start (a replacing big moment) draws again,
    /// in every theme; the task follows the start.
    func testTheCheckRedrawsForANewMoment() throws {
        XCTAssertTrue(DoneCheck.moving(tick: true, artTick: true, theme: .aurora, start: 20, landedFor: nil))
        XCTAssertFalse(DoneCheck.moving(tick: true, artTick: true, theme: .aurora, start: 20, landedFor: 20), "landed: paused")
        XCTAssertTrue(DoneCheck.moving(tick: true, artTick: true, theme: .aurora, start: 20, landedFor: 10), "a new moment draws again")
        XCTAssertTrue(DoneCheck.moving(tick: true, artTick: true, theme: .vardr, start: 20, landedFor: 10))
        XCTAssertTrue(DoneCheck.moving(tick: true, artTick: true, theme: .norse, start: 20, landedFor: 20), "Norse's ring keeps turning")
        XCTAssertFalse(DoneCheck.moving(tick: false, artTick: true, theme: .norse, start: 20, landedFor: nil), "still")
        // D-239 review (I1): under power saving the check still plays (a moment), and Norse's ring stops once it lands.
        XCTAssertTrue(DoneCheck.moving(tick: true, artTick: false, theme: .norse, start: 20, landedFor: nil), "the check plays")
        XCTAssertFalse(DoneCheck.moving(tick: true, artTick: false, theme: .norse, start: 20, landedFor: 20), "the ring stops")
        XCTAssertEqual(CheckMotion.landedElapsed, 1.2, accuracy: 1e-9)
        let check = try declaration(try appSource("DoneMoments.swift"), "struct DoneCheck: View {")
        XCTAssertTrue(check.contains(".task(id: start)"), "the task restarts for a new start")
        XCTAssertTrue(check.contains("landedFor = start"))
        XCTAssertTrue(check.contains("DoneCheck.moving(tick: tick, artTick: artTick, theme: theme, start: start, landedFor: landedFor)"))
        XCTAssertTrue(check.contains("let artTick = MotionClock.artTicks(reduceMotion: reduceMotion, active: phase == .active)"))
        XCTAssertTrue(check.contains("still: tick ? CheckMotion.landedElapsed : CheckMotion.stillElapsed"), "a landed pause holds its pose")
        XCTAssertFalse(check.contains("@State private var landed = false"), "no landing kept across moments")
    }

    /// U27: both deletes post "Group deleted", then close the settings and the group's page at once (nothing waits
    /// between); the delete for everyone no longer goes back to the group's page.
    func testDeletingAGroupSaysSoAndLeaves() throws {
        let source = try appSource("GroupSettings.swift")
        for (from, to) in [("private func deleteForEveryone(", "private func deleteHere("),
                           ("private func deleteHere(", "/// What the cloud delete's confirm shows")] {
            let body = try slice(source, from: from, to: to)
            let post = try XCTUnwrap(body.range(of: "DoneCenter.shared.post(DoneMoments.line(DoneCopy.groupDeleted))"), from)
            let gone = try XCTUnwrap(body.range(of: "leave()"), from)
            XCTAssertLessThan(post.lowerBound, gone.lowerBound, "\(from): the post, then the close")
            let between = body[post.upperBound..<gone.lowerBound]
            for wait in ["await", "sleep", "Task", "asyncAfter", "withAnimation"] {
                XCTAssertFalse(between.contains(wait), "\(from): nothing waits between the post and the close (\(wait))")
            }
            XCTAssertFalse(body.contains("dismiss()"), "\(from): never back to the group's page")
        }
        // The look's finding: the page's own dismiss left an empty Group settings on screen, so in a tab the tab goes
        // back to its root (the shell bumps the stack's id, as a re-tap does); outside the shell, the page's close.
        let leave = try slice(source, from: "private func leave() {", to: "/// A group only on this phone")
        XCTAssertTrue(leave.contains("NotificationCenter.default.post(name: .skiptiShowTabRoot, object: shellTab)"))
        XCTAssertTrue(leave.contains("onGone()"))
        let shell = try appSource("HomeShell.swift")
        XCTAssertTrue(shell.contains(".onReceive(NotificationCenter.default.publisher(for: .skiptiShowTabRoot))"))
        XCTAssertTrue(shell.contains("roots[tab, default: 0] += 1"))
        // D-240 review (M1): no back swipe while the delete for everyone runs (Android swallows Back).
        XCTAssertTrue(source.contains(".navigationBarBackButtonHidden(busy)"))
    }

    /// M4 (D24): a screen's check buzzes once per moment, never again when the screen appears again; the overlay's check
    /// never buzzes (its centre does, at `begin`).
    func testAScreenCheckBuzzesOncePerMoment() throws {
        XCTAssertTrue(DoneCheck.buzzes(.screen, start: 5, buzzedFor: nil))
        XCTAssertFalse(DoneCheck.buzzes(.screen, start: 5, buzzedFor: 5), "appeared again: no second buzz")
        XCTAssertFalse(DoneCheck.buzzes(.overlay, start: 5, buzzedFor: nil), "the centre buzzes for the overlay")
        let check = try declaration(try appSource("DoneMoments.swift"), "struct DoneCheck: View {")
        let task = try slice(check, from: ".task(id: start) {", to: "landedFor = start")
        let mark = try XCTUnwrap(task.range(of: "buzzedFor = start"))
        let buzz = try XCTUnwrap(task.range(of: "DoneCenter.shared.success()"))
        XCTAssertLessThan(mark.lowerBound, buzz.lowerBound, "marked, then played")
        XCTAssertTrue(task.contains("DoneCheck.buzzes(presentation, start: start, buzzedFor: buzzedFor)"))
        XCTAssertEqual(check.components(separatedBy: "DoneCenter.shared.success()").count, 2, "one success")
    }

    /// F9: a title breaks greedily, as Android's text does: as many words on a line as fit. iOS's own text moves a word
    /// down so a last line never holds one word alone, which broke the erased title after "ledger" where Android breaks
    /// after "is", with the same 362 of room.
    func testTheErasedTitleBreaksGreedily() throws {
        let tenEach: (String) -> CGFloat = { CGFloat($0.count * 10) }
        XCTAssertEqual(GreedyWrap.lines("aa bb cc", width: 50, measure: tenEach), ["aa bb", "cc"], "a last word alone is fine")
        XCTAssertEqual(GreedyWrap.lines("aa bb cc", width: 80, measure: tenEach), ["aa bb cc"])
        XCTAssertEqual(GreedyWrap.lines("aaaaaaaa bb", width: 50, measure: tenEach), ["aaaaaaaa", "bb"], "a long word: its own line")
        XCTAssertEqual(GreedyWrap.lines("  aa   bb ", width: 200, measure: tenEach), ["aa bb"])
        XCTAssertEqual(GreedyWrap.lines("", width: 50, measure: tenEach), [])
        let font = TypeRole.displayUIFont(28, category: .large, theme: .aurora)
        XCTAssertEqual(font.pointSize, 28, accuracy: 0.01)
        // The look's room (402 less the screen's 20 a side), in each theme's face as the title draws it.
        XCTAssertEqual(GreedyText.lines(EraseCopy.doneTitle, font: font, room: 362), ["This phone's ledger is", "erased"],
                       "Android's break, with the look's room")
        XCTAssertEqual(GreedyText.lines(EraseCopy.doneTitle, font: TypeRole.displayUIFont(28, category: .large, theme: .vardr),
                                        room: 362), ["This phone's ledger is", "erased"])
        // D-224 follow-up (R1): Norse's Cormorant SC draws the whole title 361.536 wide, so it fits the 362 on ONE line, as
        // Android draws it. The width is measured unrounded and compared with the whole room: rounding it up to 362 and
        // taking a point off the room broke it after "is" (362 > 361). The golden is re-derived by hand from the TTF
        // (.drafts/d224/fix2/ios/advance.py: hmtx advances 12947 units, GPOS kerning -35, so 12912 x 28 / 1000) and
        // checked against Core Text's own line, as the title is drawn (no tracking, no case change: the face is small caps).
        let norse = TypeRole.displayUIFont(28, category: .large, theme: .norse)
        XCTAssertEqual(norse.fontName, TypeRole.cormorantSemiBold)
        let drawn = CTLineGetTypographicBounds(CTLineCreateWithAttributedString(
            NSAttributedString(string: EraseCopy.doneTitle, attributes: [.font: norse])), nil, nil, nil)
        XCTAssertEqual(drawn, 361.536, accuracy: 0.001, "the hand-derived width")
        XCTAssertEqual(GreedyWrap.width(EraseCopy.doneTitle, font: norse), CGFloat(drawn), accuracy: 0.001, "measured as drawn")
        XCTAssertEqual(GreedyText.lines(EraseCopy.doneTitle, font: norse, room: 362), ["This phone's ledger is erased"],
                       "Norse: one line, as Android")
        XCTAssertEqual(GreedyText.lines(EraseCopy.doneTitle, font: norse, room: 361.6), ["This phone's ledger is erased"],
                       "fits by its exact width")
        XCTAssertEqual(GreedyText.lines(EraseCopy.doneTitle, font: norse, room: 361.5), ["This phone's ledger is", "erased"],
                       "and no further")
        let greedy = try declaration(try appSource("TypeSystem.swift"), "struct GreedyText: View {")
        XCTAssertEqual(greedy.components(separatedBy: "GreedyText.lines(text, font: font, room: width)").count, 2,
                       "the view breaks by the same rule")
        let erased = try declaration(try appSource("AccountOwner.swift"), "struct ErasedScreen: View {")
        XCTAssertTrue(erased.contains("GreedyText(EraseCopy.doneTitle, font: TypeRole.displayUIFont(28,"), "the title wraps greedily")
        for drawnDifferently in [".tracking(", ".kerning(", ".textCase("] {
            XCTAssertFalse(erased.contains(drawnDifferently), "the title is drawn as it is measured: \(drawnDifferently)")
        }
        XCTAssertFalse(erased.contains("Text(verbatim: EraseCopy.doneTitle)"), "not the system's own wrap")
    }

    /// D-234 (L5, decision 90): the vault recovery screen breaks every paragraph greedily, leading, as Android's text does
    /// (the D-228 look: iOS moved "this" down beside "device."); the title in the display font, the rest in the body
    /// font, as drawn before. The erased title keeps its centre (GreedyText's default).
    func testTheVaultRecoveryParagraphsBreakAsAndroidDoes() throws {
        let screen = try declaration(try appSource("VaultRecoveryView.swift"), "struct VaultRecoveryView: View {")
        for needle in [
            "GreedyText(VaultRecoveryCopy.title, font: TypeRole.displayUIFont(TypeScale.lockTitle, category: category, theme: theme), alignment: .leading)",
            "GreedyText(reason, font: paragraph, alignment: .leading)",
            "GreedyText(VaultRecoveryCopy.explain, font: paragraph, alignment: .leading)",
            "GreedyText(VaultRecoveryCopy.eraseWarning, font: paragraph, alignment: .leading)",
            "GreedyText(note.text, font: paragraph, alignment: .leading)",
            "let paragraph = TypeRole.bodyUIFont(category: category, theme: theme)",
        ] {
            XCTAssertTrue(screen.contains(needle), "VaultRecoveryView: \(needle)")
        }
        for gone in ["Text(verbatim: VaultRecoveryCopy.title)", "Text(verbatim: reason)", "Text(verbatim: VaultRecoveryCopy.explain)"] {
            XCTAssertFalse(screen.contains(gone), gone)
        }
        let greedy = try declaration(try appSource("TypeSystem.swift"), "struct GreedyText: View {")
        XCTAssertTrue(greedy.contains("init(_ text: String, font: UIFont, alignment: Alignment = .center)"))
        XCTAssertTrue(greedy.contains(".frame(maxWidth: .infinity, alignment: alignment)"))
        // The body font is the one `TypeRole.body()` draws: the theme's body face at 16, scaled with the text size.
        let f = TypeRole.bodyUIFont(category: .large, theme: .aurora)
        XCTAssertEqual(f.fontName, TypeRole.sansRegular)
        XCTAssertEqual(f.pointSize, 16, accuracy: 0.01)
    }

    // MARK: - D-231 Task 4: the Less motion row (decision 69)

    /// The Less motion row sits on Appearance after Haptics: the sparkles tile, "Less motion" over "Still art, fewer
    /// animations", a `.toggle` row on `appearance.lessMotion` read through `MotionSetting.parse` (the low-memory
    /// default until set), and no haptic of its own.
    func testTheMotionRowIsOnAppearance() throws {
        let hub = try appSource("AccountHubScreen.swift")
        let textSize = try slice(hub, from: "        case .textSize:", to: "        case .appLock:")
        let haptics = try XCTUnwrap(textSize.range(of: "HapticsRow()"))
        let motion = try XCTUnwrap(textSize.range(of: "MotionRow()"), "the row on Appearance")
        XCTAssertGreaterThan(motion.lowerBound, haptics.upperBound, "after Haptics")
        XCTAssertEqual(hub.components(separatedBy: "MotionRow()").count, 2, "one place")
        let row = try declaration(hub, "struct MotionRow: View {")
        for read in ["@AppStorage(MotionSetting.storageKey) private var stored: Bool?",
                     // D-244 (ADR-0043), amended on purpose: a tap goes through the one write of a synced setting.
                     "MotionSetting.parse(stored: stored, lowRam: MotionSetting.lowRamAtLaunch)", "set: { SettingsSync.set(MotionSetting.storageKey, $0) }",
                     "ListCard {",
                     "ListRow(glyph: .sparkles, title: MotionCopy.title, parts: [MotionCopy.summary], trailing: .toggle(isOn))"] {
            XCTAssertTrue(row.contains(read), "MotionRow reads \(read)")
        }
        for banned in ["Toggle(", "DoneCenter", "Haptics."] {
            XCTAssertFalse(row.contains(banned), "MotionRow: \(banned)")
        }
    }

    /// Lucide's sparkles (lucide-static v1.59.0, read 2026-10-19), in upstream's order: its paths verbatim, then its
    /// `<circle cx="4" cy="20" r="2">` as its two-arc path from the top. Not Feather-derived (ISC only).
    func testTheSparklesIsLucidesOwnPath() throws {
        XCTAssertEqual(LucideGlyph.sparkles.pathData, [
            "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
            "M20 2v4",
            "M22 4h-4",
            "M4 18a2 2 0 1 0 0 4 2 2 0 1 0 0-4",
        ])
        XCTAssertEqual(LucideGlyph.sparkles.rawValue, "sparkles")
        // The dot spans its circle: 2 to 6 across, 18 to 22 down; the whole drawing stays in the 24 box.
        let dot = try SVGPath.parse(LucideGlyph.sparkles.pathData[3]).cgPath.boundingBoxOfPath
        XCTAssertEqual(dot.minX, 2, accuracy: 0.01)
        XCTAssertEqual(dot.maxX, 6, accuracy: 0.01)
        XCTAssertEqual(dot.minY, 18, accuracy: 0.01)
        XCTAssertEqual(dot.maxY, 22, accuracy: 0.01)
        let all = LucideGlyph.sparkles.path().cgPath.boundingBoxOfPath
        XCTAssertGreaterThanOrEqual(all.minX, 0)
        XCTAssertGreaterThanOrEqual(all.minY, 0)
        XCTAssertLessThanOrEqual(all.maxX, 24)
        XCTAssertLessThanOrEqual(all.maxY, 24)
    }

    // MARK: - D-234 T1: every settings summary fits its row on the narrowest phones (decision 82)

    /// The slack a summary keeps in hand (rounding, hinting) and the bound Android's AWT measure multiplies Vardr Labs'
    /// default-instance widths by (it cannot drive the wght axis); `testVardrLabsSummariesStayInsideTheWeightBound`
    /// proves the bound. Android's DoneMomentsTest reads both lines. D-234 (decision 101): 1.01, not 1.05: the measured
    /// wght-400 over wght-300 ratio is under 1.005 on every Vardr Labs summary (about 0.999), and 1.05 made Android
    /// truncate "Still art, fewer animations" at 360 dp when it fits.
    private enum SummaryWidth {
        static let slack: CGFloat = 2
        static let vardrWeightBound: CGFloat = 1.01
    }

    /// One settings row as the width test sees it: what it says, how many lines it may take, and what else shares it.
    private struct SummaryRow {
        let label: String
        let parts: [String]
        var lines = 1
        var tile = false
        let trailing: CGFloat
    }

    /// The text column a row leaves its summary on a phone `phone` wide: the page's 20 a side, the card's `listPadH` a
    /// side, the tile and its gap, the trailing piece (a chevron, a switch) and its gap. `ListRow`'s own layout.
    private func column(phone: CGFloat, _ row: SummaryRow) -> CGFloat {
        phone - 2 * 20 - 2 * ThemeArt.m("listPadH") - (row.tile ? ThemeArt.m("settingsTile") + ThemeArt.m("rowGap") : 0)
            - row.trailing - ThemeArt.m("rowGap")
    }

    /// Every fixed summary a settings row draws, in `theme`, from the app's own words (a copy change is measured).
    /// Left out, as the person's own data the ellipsis is for: the email method, the Your phones line, the licences.
    private func summaryRows(_ theme: AppTheme) -> [SummaryRow] {
        let chevron = RowChevron.size, toggle = ThemeArt.m("switchWidth")
        var rows: [SummaryRow] = []
        var seen = Set<String>()
        for signedIn in [false, true] {
            for method in [nil, SignInMethod.apple.rowLabel, SignInMethod.google.rowLabel] {
                for phones in [nil, 1, 12] as [Int?] {
                    for mode in AppearanceMode.allCases {
                        for lock in LockTimeout.allCases {
                            for canLock in [true, false] {
                                let facts = YouRowFacts(signedIn: signedIn, methodLabel: method, phoneCount: phones, theme: theme,
                                                        mode: mode, lockTimeout: lock, lockAvailable: canLock, version: "1.10.10")
                                for section in SettingsSection.allCases {
                                    let parts = YouRows.parts(section, facts)
                                    if seen.insert(parts.joined(separator: "|")).inserted {
                                        rows.append(SummaryRow(label: "You, \(section)", parts: parts, tile: true, trailing: chevron))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rows.append(SummaryRow(label: "Haptics", parts: [DoneCopy.hapticsSummary], tile: true, trailing: toggle))
        rows.append(SummaryRow(label: "Less motion", parts: [MotionCopy.summary], tile: true, trailing: toggle))
        for lock in LockTimeout.allCases {
            rows.append(SummaryRow(label: "App lock", parts: [SecurityRows.appLockLine(timeout: lock, available: true)], trailing: chevron))
        }
        rows.append(SummaryRow(label: "App lock", parts: [SecurityRows.appLockLine(timeout: .off, available: false)], trailing: chevron))
        rows.append(SummaryRow(label: "Erase", parts: [SecurityRows.eraseSummary], trailing: chevron))
        rows.append(SummaryRow(label: "Delete account", parts: [SecurityRows.deleteAccountSummary], trailing: chevron))
        rows.append(SummaryRow(label: "Export", parts: [YourDataCopy.exportDetail], lines: 2, trailing: chevron))
        // D-234 (decision 100): Import keeps its words and takes three lines (two cut "added." on the narrow phones).
        rows.append(SummaryRow(label: "Import", parts: [ImportCopy.entryDetail], lines: 3, trailing: chevron))
        return rows
    }

    /// The width `parts` draw at, joined by the theme's separator, in `theme`'s body face at the default text size
    /// (`TypeScale.detail`, the `.large` category the app's step 0 draws at), Norse's separator rune in the rune font as
    /// `Separator.attributed` draws it. Core Text drives Space Grotesk's wght axis, so `weight` is the real cut.
    private func summaryWidth(_ parts: [String], theme: AppTheme, weight: CGFloat = 400) -> CGFloat {
        let body = TypeRole.base(TypeRole.face(.body, theme: theme), size: TypeScale.detail, weight: weight, tabular: false)
        let text = NSMutableAttributedString(string: parts.joined(separator: Separator.meta(for: theme)), attributes: [.font: body])
        if theme == .norse {
            let rune = TypeRole.runeUIFont(TypeScale.detail, relativeTo: .body, category: .large)
            let ns = text.string as NSString
            var from = 0
            while from < ns.length {
                let found = ns.range(of: Runes.separator, options: [], range: NSRange(location: from, length: ns.length - from))
                if found.location == NSNotFound { break }
                text.addAttribute(.font, value: rune, range: found)
                from = found.location + found.length
            }
        }
        return text.size().width
    }

    /// D-234 T1 (decision 82): every summary in every theme fits its row's text column, with `slack` in hand, on the
    /// narrowest iPhone the app supports (375 pt) and on a 360 dp Android phone (the same TTFs and advances; sp is dp at
    /// the default scale): a one-line summary by its width, a two-line one by its greedy lines. `ListRow` ends a summary
    /// that does not fit in an ellipsis, so a row's height cannot show this; the width can. Android's twin is
    /// DoneMomentsTest.every_settings_summary_fits_its_row_on_the_narrowest_phones.
    func testEverySettingsSummaryFitsItsRowOnTheNarrowestPhones() throws {
        var measured = 0
        for theme in AppTheme.allCases {
            for row in summaryRows(theme) {
                for (phone, width) in [("iPhone 375 pt", CGFloat(375)), ("Android 360 dp", CGFloat(360))] {
                    let room = column(phone: width, row) - SummaryWidth.slack
                    if row.lines == 1 {
                        let drawn = summaryWidth(row.parts, theme: theme)
                        XCTAssertLessThanOrEqual(drawn, room, "\(theme) \(phone) \(row.label): \(row.parts) is \(drawn) wide in \(room)")
                    } else {
                        let lines = GreedyWrap.lines(row.parts.joined(separator: Separator.meta(for: theme)), width: room) {
                            self.summaryWidth([$0], theme: theme)
                        }
                        XCTAssertLessThanOrEqual(lines.count, row.lines, "\(theme) \(phone) \(row.label): \(lines)")
                    }
                    measured += 1
                }
            }
        }
        XCTAssertGreaterThan(measured, 100, "the table is not empty")
        // The negative: the instrument sees what the old height test could not. The old Haptics words do not fit a 360 dp
        // phone's switch row in Aurora (188.5 wide in 178).
        let haptics = SummaryRow(label: "Haptics", parts: ["A tap when something is done"], tile: true, trailing: ThemeArt.m("switchWidth"))
        XCTAssertGreaterThan(summaryWidth(haptics.parts, theme: .aurora), column(phone: 360, haptics) - SummaryWidth.slack)
        // The geometry is ListRow's and the page's: no spacer beside the words (D-224 R2), the words take the rest, the
        // chevron is RowChevron's 20, and the settings pages pad 20 a side.
        let row = try declaration(try appSource("ListRows.swift"), "struct ListRow: View {")
        XCTAssertFalse(row.contains("Spacer("), "no spacer: the words take the room, as Android's weight(1f)")
        XCTAssertEqual(row.components(separatedBy: ".frame(maxWidth: .infinity, alignment: .leading)").count, 2,
                       "the words take the rest")
        XCTAssertTrue(row.contains("HStack(spacing: ThemeArt.m(\"rowGap\")) {\n            if let glyph { SettingsTile(glyph: glyph) }"))
        XCTAssertEqual(RowChevron.size, 20)
        let screen = try slice(try appSource("AccountHubScreen.swift"), from: "struct SettingsSectionScreen: View {", to: "\n}\n")
        XCTAssertTrue(screen.contains(".padding(20)"), "the settings page's 20 a side")
    }

    /// D-234 T1 (decision 82): Android's AWT measure draws Space Grotesk's default instance (wght 300), so it multiplies
    /// by `vardrWeightBound`; this proves the bound on every Vardr Labs summary (Core Text at 400 over 300). Measured:
    /// Space Grotesk narrows slightly as it gets heavier (about 0.999 at 400), so the bound holds with room; the axis is
    /// proved driven by reading the variation back and by the widths at 300, 400 and 700 differing.
    func testVardrLabsSummariesStayInsideTheWeightBound() throws {
        for weight in [CGFloat(400), 700] {
            let font = TypeRole.base(.grotesk, size: TypeScale.detail, weight: weight, tabular: false)
            let variation = try XCTUnwrap(CTFontCopyVariation(font as CTFont) as? [NSNumber: NSNumber], "\(weight)")
            XCTAssertEqual(variation[TypeRole.weightAxis]?.doubleValue ?? -1, Double(weight), accuracy: 0.5, "the axis is driven at \(weight)")
        }
        for row in summaryRows(.vardr) {
            let at300 = summaryWidth(row.parts, theme: .vardr, weight: 300)
            let at400 = summaryWidth(row.parts, theme: .vardr, weight: 400)
            let at700 = summaryWidth(row.parts, theme: .vardr, weight: 700)
            XCTAssertNotEqual(at400, at300, "the axis is driven: \(row.parts)")
            XCTAssertNotEqual(at700, at400, "the axis is driven: \(row.parts)")
            XCTAssertLessThanOrEqual(at400 / at300, SummaryWidth.vardrWeightBound, "\(row.parts): \(at400) over \(at300)")
        }
    }
}
