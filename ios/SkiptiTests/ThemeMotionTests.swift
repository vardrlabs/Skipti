import CoreText
import SwiftUI
import XCTest
@testable import Skipti

/// D-214 (E2 motion, ADR-0041 "Theme art"): every animation is a pure function of time; goldens from
/// .drafts/d214/motion.py, which ports the Look Book's own formulas. Android's twin is ThemeMotionTest.
/// D-246: motion.py 5a's printout (.drafts/d246/motion-after2.out since the fix wave, "D-246 glyph peaks"): each Futhark glyph's blurred mask's
/// largest coverage at blur 0.18 x the size, and each layer's largest (the floors take it under the full core).
enum GlyphGlowGoldens {
    static let peaks: [(String, Double)] = [
        ("\u{16A0}", 0.2319), ("\u{16A2}", 0.2203), ("\u{16A6}", 0.2498), ("\u{16A8}", 0.2454), ("\u{16B1}", 0.2641),
        ("\u{16B2}", 0.1373), ("\u{16B7}", 0.2101), ("\u{16B9}", 0.2322), ("\u{16BA}", 0.2657), ("\u{16BE}", 0.2179),
        ("\u{16C1}", 0.1572), ("\u{16C3}", 0.1145), ("\u{16C7}", 0.1739), ("\u{16C8}", 0.2081), ("\u{16C9}", 0.2604),
        ("\u{16CA}", 0.1998), ("\u{16CF}", 0.2157), ("\u{16D2}", 0.2817), ("\u{16D6}", 0.2572), ("\u{16D7}", 0.3031),
        ("\u{16DA}", 0.1797), ("\u{16DC}", 0.1961), ("\u{16DE}", 0.3430), ("\u{16DF}", 0.2247),
    ]
    static let page = 0.3430
    static let hero = 0.2657
    static let fehu = 0.2319
    /// D-246 fix wave (I1): each layer's MEASURED ink reach (x size from the box's centre, motion.py 5a "ink reach"): the
    /// receiving rune's ink wherever a glow lands on another rune (1d, 1e), where D-243's 0.30 disc stood.
    static let inkPage = 0.4569
    static let inkHero = 0.4463
    /// motion.py 1d's lane bound (.drafts/d246/motion-after2.out): the most glow a hero row's measured ink takes from the
    /// other rows at their lane distances, each at the top of its flare, summed (row 1, 328 wide).
    static let heroLaneGlow = 0.011468
}

final class ThemeMotionTests: XCTestCase {
    private func near(_ a: Double, _ b: Double, _ eps: Double = 1e-4, _ msg: String = "") {
        XCTAssertEqual(a, b, accuracy: eps, msg)
    }

    func testOnlyAnActiveScreenWithMotionAllowedTicks() {
        XCTAssertTrue(MotionClock.shouldTick(reduceMotion: false, lowPower: false, active: true))
        XCTAssertFalse(MotionClock.shouldTick(reduceMotion: true, lowPower: false, active: true))
        XCTAssertFalse(MotionClock.shouldTick(reduceMotion: false, lowPower: true, active: true))
        XCTAssertFalse(MotionClock.shouldTick(reduceMotion: false, lowPower: false, active: false))
        XCTAssertEqual(MotionClock.fps, 30)
    }

    /// D-239 (decision 137): a moment ignores power saving; only Reduce Motion or Less motion, or a screen not in front,
    /// stills it. The art keeps the Low Power rule (above).
    func testAMomentMovesUnderPowerSavingAndTheArtDoesNot() {
        XCTAssertTrue(MotionClock.momentTicks(reduceMotion: false, active: true))
        XCTAssertFalse(MotionClock.momentTicks(reduceMotion: true, active: true))
        XCTAssertFalse(MotionClock.momentTicks(reduceMotion: false, active: false))
        XCTAssertFalse(MotionClock.shouldTick(reduceMotion: false, lowPower: true, active: true), "the art still pauses")
    }

    /// D-239 (decision 137): only the art reads the phone's power saving. Every other read of `PowerState.shared.lowPower`
    /// in the app would still a moment again (the founder's iPhone 17 froze every animation under Adaptive Power).
    func testOnlyTheArtReadsPowerSaving() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        var readers: [String: Int] = [:]
        var direct: [String: Int] = [:]
        for name in try FileManager.default.contentsOfDirectory(atPath: app.path) where name.hasSuffix(".swift") {
            let text = try String(contentsOf: app.appendingPathComponent(name), encoding: .utf8)
            // D-239 review (M1): any `.lowPower` read (a local PowerState, an alias), not only the shared one's text.
            let n = text.components(separatedBy: ".lowPower").count - 1
            if n > 0 { readers[name] = n }
            let d = text.components(separatedBy: "isLowPowerModeEnabled").count - 1
            if d > 0 { direct[name] = d }
        }
        // ThemeMotion.swift: the art canvas, the runes, `MotionClock.artTicks` and PowerState's own observer.
        XCTAssertEqual(readers, ["ThemeMotion.swift": 4])
        XCTAssertTrue(try String(contentsOf: app.appendingPathComponent("ThemeMotion.swift"), encoding: .utf8)
            .contains("@MainActor static func artTicks(reduceMotion: Bool, active: Bool) -> Bool {\n        shouldTick(reduceMotion: reduceMotion, lowPower: PowerState.shared.lowPower, active: active)"))
        // The system's flag is read by PowerState alone (its first value and its observer), so no moment can read
        // power saving around the shared state.
        XCTAssertEqual(direct, ["ThemeMotion.swift": 2])
        // The canvas lets a moment (the burst) tick under power saving.
        let motion = try String(contentsOf: app.appendingPathComponent("ThemeMotion.swift"), encoding: .utf8)
        XCTAssertTrue(motion.contains("let tick = moment ? MotionClock.momentTicks(reduceMotion: reduceMotion, active: active)"))
    }

    func testTheCurtainsSwayAsTheLookBookDoes() {
        let want: [(Int, Double, [Double])] = [
            (0, 0, [-0.184, -0.12, -16, 1, 1]), (0, 3.25, [-0.121, -0.113, -5, 1.025, 1.075]),
            (0, 6.5, [-0.058, -0.106, 6, 1.05, 1.15]), (0, 13, [-0.002, -0.134, -8, 0.95, 1]),
            (0, 19.5, [-0.058, -0.106, 6, 1.05, 1.15]),
            (1, 0, [0.316966, -0.110705, 1.628981, 1.040066, 1.120198]),
            (2, 0, [0.537966, -0.114517, -3.434537, 1.028558, 1.085674]),
            // Later times, where a period or direction bug would show past the delay (motion.py's curtain_pose).
            (1, 9.5, [0.393806, -0.129863, -6.95152, 0.957489, 1.011234]),
            (2, 7.25, [0.64547, -0.128732, -7.367424, 0.954518, 1.006778]),
        ]
        for (i, t, w) in want {
            let p = Curtains.pose(i, t)
            for (got, exp) in zip([p.x, p.y, p.skewDegrees, p.sx, p.sy], w) { near(got, exp, 1e-5, "curtain \(i) at \(t)") }
        }
        XCTAssertEqual(Curtains.specs.map(\.period), [13, 17, 11])
    }

    /// The Look Book's curtain and sky data, pinned so a stray edit is seen (Android's parity test reads these lines).
    func testTheCurtainDataIsTheLookBooks() {
        XCTAssertEqual(Curtains.still, 0)
        XCTAssertEqual(Curtains.width, 0.70)
        XCTAssertEqual(Curtains.top, -0.12)
        XCTAssertEqual(Curtains.specs.map(\.left), [-0.10, 0.30, 0.55])
        XCTAssertEqual(Curtains.specs.map(\.height), [0.70, 0.58, 0.48])
        XCTAssertEqual(Curtains.specs.map(\.delay), [0, -6, -3])
        XCTAssertEqual(Curtains.specs.map(\.lightOpacity), [0.35, 0.35, 0.35])
        XCTAssertEqual(Curtains.specs.map(\.nightOpacity), [0.80, 0.62, 0.50])
        func flat(_ s: [Curtains.Stop]) -> [Double] { s.flatMap { [$0.r, $0.g, $0.b, $0.alpha, $0.at] } }
        let pgc: [Double] = [214, 92, 170, 0, 0, 214, 92, 170, 0.42, 0.12, 69, 230, 176, 0.85, 0.34, 31, 182, 201, 0.35, 0.64, 31, 182, 201, 0, 1]
        XCTAssertEqual(flat(Curtains.specs[0].stops), pgc)
        XCTAssertEqual(flat(Curtains.specs[1].stops), pgc)
        XCTAssertEqual(flat(Curtains.specs[2].stops), [69, 230, 176, 0, 0, 69, 230, 176, 0.70, 0.30, 31, 182, 201, 0.30, 0.70, 31, 182, 201, 0, 1])
        XCTAssertEqual(Curtains.keys, [[-0.12, 0, -16, 1, 1], [0.06, 0.02, 6, 1.05, 1.15], [0.14, -0.02, -8, 0.95, 1]])
        let stars: [[Double]] = [[0.12, 0.18, 1, 0.9], [0.33, 0.09, 1, 0.7], [0.61, 0.14, 1.2, 0.9], [0.82, 0.26, 1, 0.6],
                                 [0.47, 0.31, 1, 0.5], [0.90, 0.08, 1, 0.8], [0.24, 0.44, 1, 0.4], [0.70, 0.40, 1, 0.5]]
        XCTAssertEqual(Stars.all.map { [$0.x, $0.y, $0.radius, $0.alpha] }, stars)
        // The streaks' pattern and its mean.
        XCTAssertEqual(Curtains.streakPitch, 11)
        XCTAssertEqual(Curtains.streakBars.map { [$0.x, $0.width] }, [[0, 2], [6, 1]])
        XCTAssertEqual(Curtains.streakMean, 0.60, accuracy: 1e-12)
    }

    /// F4: the arched hem, the Look Book's `border-radius: 0 0 50% 50% / 0 0 30% 30%`: square above 0.70 of the height,
    /// two quarter ellipses below; the drawn cubics lie on that ellipse.
    func testTheCurtainsHangInAnArchedHem() {
        XCTAssertEqual(Curtains.hemTop, 0.70)
        XCTAssertTrue(Curtains.insideHem(0.01, 0.69), "square above the hem")
        XCTAssertTrue(Curtains.insideHem(0.5, 0.99), "the arch's lowest point is the bottom centre")
        XCTAssertFalse(Curtains.insideHem(0.02, 0.98), "a lower corner is cut away")
        XCTAssertFalse(Curtains.insideHem(0.98, 0.95), "the other lower corner too")
        XCTAssertFalse(Curtains.insideHem(0.5, 1.01))
        XCTAssertTrue(Curtains.insideHem(0.5 + 0.5 * cos(0.6) * 0.999, 0.70 + 0.30 * sin(0.6) * 0.999), "just inside the ellipse")
        XCTAssertFalse(Curtains.insideHem(0.5 + 0.5 * cos(0.6) * 1.001, 0.70 + 0.30 * sin(0.6) * 1.001), "just outside it")
        let (w, h) = (281.4, 611.8)
        let curves = Curtains.hemCurves(w, h)
        XCTAssertEqual(curves.count, 2)
        var from = (w, h * 0.70)
        for k in curves {
            // The cubic's midpoint sits on the ellipse (radius error under 0.1%).
            func mid(_ a: Double, _ b: Double, _ c: Double, _ d: Double) -> Double { (a + 3 * b + 3 * c + d) / 8 }
            let (mx, my) = (mid(from.0, k[0], k[2], k[4]), mid(from.1, k[1], k[3], k[5]))
            let r = (pow((mx - w / 2) / (w / 2), 2) + pow((my - h * 0.70) / (h * 0.30), 2)).squareRoot()
            XCTAssertEqual(r, 1, accuracy: 1e-3)
            from = (k[4], k[5])
        }
        XCTAssertEqual(from.0, 0, accuracy: 1e-9)
        XCTAssertEqual(from.1, h * 0.70, accuracy: 1e-9)
        // The renderer fills that outline.
        let path = ArtDraw.hem(CGFloat(w), CGFloat(h))
        for (fx, fy) in [(0.02, 0.98), (0.5, 0.95), (0.98, 0.95), (0.1, 0.5), (0.9, 0.8), (0.2, 0.9)] {
            XCTAssertEqual(path.contains(CGPoint(x: fx * w, y: fy * h)), Curtains.insideHem(fx, fy), "\(fx), \(fy)")
        }
    }

    /// One full period of the slowest curtain, sampled: the curtain always hangs from above the top and spans the width it should.
    func testEveryCurtainStaysInItsBand() {
        for i in 0..<3 {
            for step in 0...340 {
                let p = Curtains.pose(i, Double(step) * 0.1)
                XCTAssertLessThanOrEqual(p.y, -0.10, "curtain \(i) top at \(step)")
                XCTAssertGreaterThan(p.x + p.width, 0, "curtain \(i) leaves the left")
                XCTAssertLessThan(p.x, 1, "curtain \(i) leaves the right")
            }
        }
    }

    func testTheRunesRiseAsTheLookBookDoes() {
        let size = CGSize(width: 402, height: 874)
        let want: [(Int, Double, String, Bool, [Double])] = [
            // D-243 stage 3: x moved with the lanes (8.04, 156.78, 156.78, 12.06, 20.1 before; motion-lanes.out section 3)
            (0, 0, "\u{16A0}", true, [32.696, 904.0, 14, 0, 0]),
            (1, 0, "\u{16B9}", false, [205.288, 459.875, 19, 6.6957, 0.140739]),
            (1, 11.5, "\u{16B9}", false, [205.288, -4.4375, 19, 13.6957, 0.014493]),
            (5, 30, "\u{16C3}", false, [57.352, 500.25, 25, 6.087, 0.145503]),
            (15, 7, "\u{16BE}", true, [82.008, 258.0, 19, 9.7391, 0.116915]),
        ]
        for (i, t, glyph, silver, w) in want {
            let p = RuneDrift.pose(i, t, in: size)
            XCTAssertEqual(p.glyph, glyph)
            XCTAssertEqual(p.silver, silver)
            for (got, exp) in zip([p.x, p.y, p.size, p.rotationDegrees, p.opacity], w) { near(got, exp, 1e-3, "rune \(i) at \(t)") }
        }
        let still = RuneDrift.still(1, in: size)
        for (got, exp) in zip([still.x, still.y, still.rotationDegrees, still.opacity], [205.288, 515.66, 0, 0.10]) { near(got, exp, 1e-3) }
        XCTAssertEqual(RuneDrift.count, 16)
    }

    /// Over a full cycle each rune stays inside the page: the Look Book anchors x at the glyph's left edge, so x >= 0 and
    /// x + size <= width keep its em box inside (the shells centre the glyph on x, half a glyph off the Look Book, which
    /// BUILD-LOG D-214 records); it turns at most 14 degrees and never passes 0.18.
    func testEveryRuneStaysInItsBox() {
        let size = CGSize(width: 402, height: 874)
        for i in 0..<RuneDrift.count {
            for step in 0...300 {
                let p = RuneDrift.pose(i, Double(step) * 0.1, in: size)
                XCTAssertTrue(p.x >= 0 && p.x + p.size <= size.width, "rune \(i) x")
                XCTAssertTrue(p.rotationDegrees >= 0 && p.rotationDegrees <= 14, "rune \(i) rotation")
                XCTAssertTrue(p.opacity >= 0 && p.opacity <= 0.18 + 1e-9, "rune \(i) opacity")
                XCTAssertTrue(p.y <= size.height + 30 && p.y >= size.height + 30 - size.height * 680 / 640 - 1e-6, "rune \(i) y")
            }
        }
    }

    /// D-243 stage 3 (the founder, 2026-10-20: "Give each rune its own lane"; .drafts/d214/motion.py 1e and section 6,
    /// retyped from .drafts/d243/motion-lanes.out): the sixteen page runes sit one per lane, evenly spread from 2% to 94%
    /// of the width; their x on 402 and their still layout are motion.py's (the still y did not move). On the narrowest
    /// page (360) no two runes' inks (a disc of 0.30 x size about the centre, motion.py's INK) can meet at any height:
    /// every two lanes are at least 0.30 x (s_i + s_j) apart. Negative: the same rule refuses a table with two lanes 1%
    /// apart.
    func testEachRuneHasItsOwnLane() {
        XCTAssertEqual(RuneDrift.lanes, [1, 8, 14, 5, 11, 2, 7, 15, 6, 10, 0, 9, 13, 4, 12, 3])
        XCTAssertEqual(Set(RuneDrift.lanes), Set(0..<RuneDrift.count), "one rune per lane")
        let page = CGSize(width: 402, height: 874)
        let x402: [Double] = [32.696, 205.288, 353.224, 131.32, 279.256, 57.352, 180.632, 377.88, 155.976, 254.6, 8.04, 229.944, 328.568, 106.664, 303.912, 82.008]
        let still: [(Double, Double)] = [(32.696, 874.0), (205.288, 515.66), (353.224, 157.32), (131.32, 585.58), (279.256, 227.24), (57.352, 655.5), (180.632, 297.16), (377.88, 725.42), (155.976, 367.08), (254.6, 795.34), (8.04, 437.0), (229.944, 865.26), (328.568, 506.92), (106.664, 148.58), (303.912, 576.84), (82.008, 218.5)]
        for i in 0..<RuneDrift.count {
            near(RuneDrift.pose(i, 0, in: page).x, x402[i], 1e-4, "rune \(i) x")
            near(RuneDrift.pose(i, 17.3, in: page).x, x402[i], 1e-4, "rune \(i) keeps its lane")
            let s = RuneDrift.still(i, in: page)
            near(s.x, still[i].0, 1e-4, "still \(i) x")
            near(s.y, still[i].1, 1e-4, "still \(i) y")
        }
        func inkApart(_ xs: [Double], _ sizes: [Double]) -> Bool {
            for i in xs.indices {
                for j in xs.indices where j > i && abs(xs[i] - xs[j]) < 0.30 * (sizes[i] + sizes[j]) { return false }
            }
            return true
        }
        let narrow = CGSize(width: 360, height: 740)
        let xs = (0..<RuneDrift.count).map { RuneDrift.pose($0, 0, in: narrow).x }
        let sizes = (0..<RuneDrift.count).map { RuneDrift.pose($0, 0, in: narrow).size }
        XCTAssertTrue(inkApart(xs, sizes), "no two runes' inks meet on a 360 page")
        let sorted = xs.sorted()
        near(sorted[0], 0.02 * 360, 1e-9, "the first lane at 2%")
        near(sorted[15], 0.94 * 360, 1e-9, "the last lane at 94%")
        for (a, b) in zip(sorted, sorted.dropFirst()) { near(b - a, 360 * 0.92 / 15, 1e-9, "lanes evenly spread") }
        var crowded = xs
        crowded[1] = crowded[0] + 0.01 * 360
        XCTAssertFalse(inkApart(crowded, sizes), "two lanes 1% apart fail the ink rule")
    }

    func testTheCurrencyFieldIsTheLookBooksSeededField() throws {
        let first = CurrencyField.draws(3)
        near(first[0], 0.000054784, 1e-9); near(first[1], 0.920764517, 1e-9); near(first[2], 0.289237255, 1e-9)
        func floater(_ g: String) throws -> CurrencyField.Floater { try XCTUnwrap(CurrencyField.floaters.first { $0.glyph == g }) }
        let dollar = try floater("$"), rupee = try floater("\u{20B9}"), euro = try floater("\u{20AC}")
        near(dollar.sx, 0.332343, 1e-6); near(dollar.sy, 0.237197, 1e-6); near(dollar.ph, 2.069069, 1e-6); near(dollar.rot, 0.127537, 1e-6)
        XCTAssertTrue(dollar.cyan)
        near(rupee.sx, 0.243558, 1e-6); near(rupee.ph, 4.385821, 1e-6); XCTAssertTrue(rupee.cyan); XCTAssertEqual(rupee.size, 40)
        near(euro.sx, 0.380792, 1e-6); near(euro.rot, 0.205998, 1e-6)
        let poses: [(CurrencyField.Floater, Double, [Double])] = [
            (dollar, 0, [0.64196, 0.635017, 0.232947, 0.648208, 36]), (dollar, 4, [0.613649, 0.636049, 0.112282, 0.606685, 36]),
            (dollar, 10, [0.600562, 0.697115, 0.01509, 0.587491, 36]), (rupee, 0, [0.776321, 0.361779, -0.021703, 0.66, 48]),
            (rupee, 10, [0.812815, 0.291563, 0.199043, 0.66, 48]),
        ]
        for (f, t, w) in poses {
            let p = CurrencyField.pose(f, t)
            for (got, exp) in zip([p.x, p.y, p.rotation, p.alpha, p.size], w) { near(got, exp, 1e-5, "\(f.glyph) at \(t)") }
        }
        XCTAssertEqual(CurrencyField.signs, ["$", "\u{20B9}", "\u{20AC}", "\u{00A3}", "\u{00A5}", "\u{20A9}", "\u{20BD}", "\u{0E3F}", "\u{20BA}",
                                             "\u{20A6}", "\u{20B1}", "\u{20AA}", "\u{20AB}", "\u{20B4}", "\u{5143}", "R$", "kr", "\u{00A2}",
                                             "\u{20B8}", "A$", "C$", "z\u{0142}"])
        XCTAssertEqual(CurrencyField.still, 4)
    }

    /// Every centre stays inside the hero over 200 s; the five majors stay on its right side (x >= 0.55).
    func testEverySignStaysInsideTheHeroAndTheMajorsStayRight() {
        let majors: Set<String> = ["$", "\u{20B9}", "\u{20AC}", "\u{00A3}", "\u{00A5}"]
        for f in CurrencyField.floaters {
            for step in 0...400 {
                let p = CurrencyField.pose(f, Double(step) * 0.5)
                XCTAssertTrue(p.x > 0 && p.x < 1 && p.y > 0 && p.y < 1, "\(f.glyph) leaves the hero")
                if majors.contains(f.glyph) { XCTAssertGreaterThanOrEqual(p.x, 0.55, "\(f.glyph) drifts left") }
            }
        }
    }

    func testTheBoltTurnsOnlyWhenPressedAndOnlyTheBolt() {
        XCTAssertEqual(AddButtonMotion.turn(art: .bolt, pressed: true, reduceMotion: false), 30)
        XCTAssertEqual(AddButtonMotion.turn(art: .bolt, pressed: false, reduceMotion: false), 0)
        XCTAssertEqual(AddButtonMotion.turn(art: .bolt, pressed: true, reduceMotion: true), 0)
        XCTAssertEqual(AddButtonMotion.turn(art: .diamond, pressed: true, reduceMotion: false), 0)
        XCTAssertEqual(AddButtonMotion.turn(art: .circle, pressed: true, reduceMotion: false), 0)
    }

    /// The strengths motion.py prints (section 1b, on 8-bit pixels since the founder's rule of 2026-10-15); a typo'd key
    /// would trap at first draw, so the set is pinned.
    func testTheStrengthsAreTheMeasuredOnes() {
        XCTAssertEqual(Set(Strength.metrics.keys), [
            "auroraDarkPageCurtains", "auroraDarkPageStars", "auroraLightPageCurtains", "auroraHeroCurtains",
            "auroraHeroStars", "norsePageRunes", "norseHero", "vardrLightHeroSigns", "vardrDarkHeroSigns", "norsePageGlow",
            "norseHeroRunes",
        ])
        XCTAssertEqual(Strength.of("auroraDarkPageCurtains"), 0.26)
        XCTAssertEqual(Strength.of("auroraDarkPageStars"), 0.16)
        XCTAssertEqual(Strength.of("auroraLightPageCurtains"), 1.0)
        XCTAssertEqual(Strength.of("auroraHeroCurtains"), 1.0)
        XCTAssertEqual(Strength.of("auroraHeroStars"), 1.0)
        XCTAssertEqual(Strength.of("norsePageRunes"), 0.79)
        XCTAssertEqual(Strength.of("norseHero"), 1.0)
        XCTAssertEqual(Strength.of("vardrLightHeroSigns"), 0.60)
        XCTAssertEqual(Strength.of("vardrDarkHeroSigns"), 0.54)
        XCTAssertEqual(Strength.of("norsePageGlow"), 1.0, "D-226: art.py section 48, the Look Book's own strength")
        XCTAssertEqual(Strength.of("norseHeroRunes"), 0.83, "D-243: motion.py section 6, the hero runes' rest (0.20 x 0.142 / 0.19 / 0.18)")
    }

    /// D-226 (L34, .drafts/d226/art.py section 48): Norse's two page glows, the Look Book's radials (LB 220), in draw
    /// order: gold at the top centre, silver below the bottom; their colours are the rune gold and the art's silver.
    /// Android's ThemeMotionTest reads this table.
    func testTheNorseGlowTable() {
        XCTAssertEqual(NorseGlow.specs, [
            NorseGlow.Spec(hex: "#DFB043", alpha: 0.09, radiusX: 0.90, radiusY: 0.60, centreX: 0.5, centreY: 0.0, clearAt: 0.70),
            NorseGlow.Spec(hex: "#9AA2AE", alpha: 0.07, radiusX: 1.20, radiusY: 0.80, centreX: 0.5, centreY: 1.20, clearAt: 0.60),
        ])
        XCTAssertEqual(NorseGlow.specs[0].hex, ArtDraw.runeGoldHex)
        XCTAssertEqual(NorseGlow.specs[1].hex, ThemeArt.hex["norseSilver"])
    }

    // MARK: - D-226 Task 2: the drawing

    /// The text of `ThemeMotion.swift` from `start` up to the first `end` after it.
    private func motionSection(_ start: String, to end: String) throws -> String {
        let after = try XCTUnwrap(try appSource("ThemeMotion.swift").components(separatedBy: start).dropFirst().first, start)
        return try XCTUnwrap(after.components(separatedBy: end).first)
    }

    /// D-226 (Q7 a, L33): the stars draw over the curtains (the curtain layer first, then the stars source-over), on the
    /// page and the hero alike (one drawing). The negative is today's order, stars under the layer.
    func testStarsDrawOverTheCurtains() throws {
        let curtains = try motionSection("static func curtains(", to: "static func hem(")
        let stars = try XCTUnwrap(curtains.range(of: "for s in Stars.all"), "the stars are drawn here")
        let layer = try XCTUnwrap(curtains.range(of: "outer.drawLayer"), "the curtain layer is drawn here")
        XCTAssertGreaterThan(stars.lowerBound, layer.lowerBound, "the stars come after the curtain layer")
        XCTAssertEqual(curtains.components(separatedBy: "for s in Stars.all").count - 1, 1, "drawn once")
        let tail = String(curtains[stars.lowerBound...])
        XCTAssertTrue(tail.contains("ctx.fill("), "on the unblurred context")
        XCTAssertFalse(tail.contains("outer.fill(") || tail.contains("layer.fill("), "never through the curtains' blur")
    }

    /// D-226 (L34): Norse's page ground is the page colour, its two glows, then the runes; the glows read their table and
    /// their strength.
    func testTheNorseGroundHasItsGlows() throws {
        let runes = try motionSection("            case .runes:", to: "            case .grid:")
        let bg = try XCTUnwrap(runes.range(of: "Tokens.bg"))
        let glow = try XCTUnwrap(runes.range(of: "NorseGlowView()"), "the glows are on the Norse ground")
        let layer = try XCTUnwrap(runes.range(of: "RuneLayer()"))
        XCTAssertLessThan(bg.lowerBound, glow.lowerBound, "over the page colour")
        XCTAssertLessThan(glow.lowerBound, layer.lowerBound, "under the runes")
        let view = try motionSection("struct NorseGlowView: View {", to: "\n}\n")
        XCTAssertTrue(view.contains("for spec in NorseGlow.specs"), "every glow in the table")
        XCTAssertTrue(view.contains(#"Strength.of("norsePageGlow")"#), "at its strength")
        XCTAssertTrue(view.contains("spec.alpha * strength"), "the strength scales each glow's alpha")
        XCTAssertTrue(view.contains("endRadius: spec.clearAt"), "clear at the table's stop")
        XCTAssertTrue(view.contains("scaleBy(x: size.width * spec.radiusX, y: size.height * spec.radiusY)"),
                      "elliptical: Dawn's technique")
        XCTAssertFalse(view.contains("ArtCanvas"), "still: no motion")
    }

    /// D-226 (L31, Q8 a): Norse's hero is two stops at CSS 160 degrees for its own size, and its watermark and inner
    /// frame read the table; the old literals are gone.
    func testTheNorseHeroIsTwoStopsAt160() throws {
        let hero = try motionSection("struct HeroArt: View {", to: "// MARK: - D-221 Task 8")
        let norse = try motionSection("            case .norse:\n                GeometryReader", to: "            case .vardr:")
        XCTAssertTrue(norse.contains("CssGradient.unitPoints(degrees: 160, width: geo.size.width, height: geo.size.height)"))
        XCTAssertTrue(norse.contains("LinearGradient(colors: [Tokens.heroStart, Tokens.heroEnd], startPoint: ends.start, endPoint: ends.end)"))
        XCTAssertFalse(norse.contains("Tokens.heroGradient"), "not the three-stop corner gradient")
        // D-243 (amended on purpose): the ᚠ moved onto the hero's art canvas (RuneLayer(place: .hero)), so its three keys
        // are read where it is drawn now: its symbol and `ArtDraw.watermark`. The frame's inset stays in HeroArt.
        XCTAssertTrue(norse.contains(#"ThemeArt.m("heroFrameInset")"#), "heroFrameInset")
        let fehu = try motionSection("static func fehuSymbol() -> some View {", to: "\n    }\n")
        XCTAssertTrue(fehu.contains(#"ThemeArt.m("heroWatermark")"#), "heroWatermark")
        let watermark = try motionSection("static func watermark(_ ctx: inout GraphicsContext,", to: "\n    }\n")
        for key in ["heroWatermarkTrailing", "heroWatermarkTop"] {
            XCTAssertTrue(watermark.contains(#"ThemeArt.m("\#(key)")"#), key)
        }
        // D-243 fix wave (M1): the ᚠ box's origin snaps to the device-pixel grid, as D-226's Text did.
        for needle in ["let scale = max(ctx.environment.displayScale, 1)",
                       #"let left = ((size.width - ThemeArt.m("heroWatermarkTrailing") - glyph.size.width) * scale).rounded() / scale"#,
                       #"let top = (ThemeArt.m("heroWatermarkTop") * scale).rounded() / scale"#,
                       "let centre = CGPoint(x: left + glyph.size.width / 2, y: top + glyph.size.height / 2)"] {
            XCTAssertTrue(watermark.contains(needle), needle)
        }
        // D-243: the art layer sits over the gradient and under the frame and the edge; no Text of its own (the ᚠ is the
        // canvas's cached symbol).
        let gradient = try XCTUnwrap(norse.range(of: "LinearGradient(colors: [Tokens.heroStart, Tokens.heroEnd]"))
        let art = try XCTUnwrap(norse.range(of: "RuneLayer(place: .hero)"), "the hero's runes and its ᚠ")
        let frame = try XCTUnwrap(norse.range(of: #"Rectangle().strokeBorder(Tokens.accent.opacity(0.25 * Strength.of("norseHero")), lineWidth: 1)"#))
        let edge = try XCTUnwrap(norse.range(of: "NotchEdges("))
        XCTAssertTrue(gradient.lowerBound < art.lowerBound && art.lowerBound < frame.lowerBound && frame.lowerBound < edge.lowerBound,
                      "gradient, art, frame, edge")
        XCTAssertEqual(norse.components(separatedBy: "RuneLayer(").count - 1, 1)
        XCTAssertFalse(norse.contains("Text("), "the ᚠ is drawn by the canvas")
        // The hero's runes are clipped to the inner frame's interior (the frame's inset plus its 1 pt line).
        let heroRunes = try motionSection("static func heroRunes(_ ctx: inout GraphicsContext,", to: "\n    }\n")
        XCTAssertTrue(heroRunes.contains(#"let inset = ThemeArt.m("heroFrameInset") + 1"#))
        XCTAssertTrue(heroRunes.contains("inner.clip(to: Path(CGRect(origin: .zero, size: size).insetBy(dx: inset, dy: inset)))"))
        XCTAssertTrue(heroRunes.contains("glowingRune(&inner,"), "every hero rune is drawn clipped")
        XCTAssertFalse(heroRunes.contains("glowingRune(&ctx,"), "none unclipped")
        for literal in ["92", ".padding(5)", ".padding(.trailing, 10)", "offset(y: -6)"] {
            XCTAssertFalse(hero.contains(literal), "HeroArt keeps \(literal)")
        }
        // D-226 fix wave (I1, decision 29): the hero's outer edge is the straight sides only, 1 pt inside and clipped, as
        // a Norse card's and Android's `notchEdge`; never the whole notched outline with its diagonals lit.
        XCTAssertEqual(norse.components(separatedBy: "NotchEdges(").count - 1, 1, "the hero edges its straight sides")
        XCTAssertEqual(norse.components(separatedBy: #"NotchEdges(chamfer: ThemeArt.m("chamfer")).stroke(ThemeArt.norseEdge, lineWidth: 1).clipShape(ThemeArt.cardShape(.norse))"#).count - 1, 1)
        XCTAssertFalse(norse.contains("cardShape(.norse).stroke("), "the whole outline")
    }

    // MARK: - Task 3: the renderer

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The renderer ticks at 30 fps at most, pauses through shouldTick, and the page ground draws the art.
    func testTheRendererIsThrottledAndPausable() throws {
        let motion = try appSource("ThemeMotion.swift")
        XCTAssertTrue(motion.contains("TimelineView(ArtSchedule(paused: !tick)) { timeline in"), "on the shared grid, paused when it does not tick")
        XCTAssertFalse(motion.contains("TimelineView(.animation"), "no per-view schedule (P2)")
        XCTAssertTrue(motion.contains("draw(&ctx, size, Self.seconds(tick: tick, now:"), "the Canvas draws at the guarded time")
        XCTAssertTrue(motion.contains("MotionClock.shouldTick("))
        // F8: one blur for the three curtains, dithered, drawn as one layer.
        XCTAssertEqual(motion.components(separatedBy: ".addFilter(").count - 1, 1, "one blur per canvas")
        // D-246 (amended on purpose; D-243's were cached radial discs): the rune glows are the glyphs' own masks, blurred
        // ONCE by GlyphGlow's own Gaussian when first asked for, never by a filter per frame: still the curtains' one blur
        // in the file, and no shadow.
        XCTAssertEqual(motion.components(separatedBy: ".blur(").count - 1, 1, "no blur but the curtains'")
        XCTAssertEqual(motion.components(separatedBy: ".shadow(").count - 1, 0, "no shadow in the art")
        XCTAssertTrue(motion.contains("outer.addFilter(.blur(radius: night ? 9 : 14, options: .dithersResult))"))
        XCTAssertTrue(motion.contains("outer.drawLayer { layer in"))
        XCTAssertTrue(motion.contains("@Environment(\\.artVisible)"))
        XCTAssertTrue(try appSource("DesignTokens.swift").contains("PageArt()"))
        XCTAssertFalse(try appSource("DesignTokens.swift").contains("AuroraGlow"), "the blooms are gone (E1 residue (c))")
        XCTAssertTrue(try appSource("HomeShell.swift").contains(".environment(\\.artVisible, shown)"))
    }

    /// P2 (fix wave 3): every canvas updates on one grid, the multiples of 1 / 30 s from the reference date, so two
    /// arts that appeared at different moments update at the SAME dates (the window renders 30 times a second, not 30
    /// per art on alternating frames); 30 a second exactly; and a paused schedule has no updates at all.
    func testEveryArtUpdatesOnTheSameGrid() {
        func dates(from start: Double, _ n: Int, paused: Bool = false) -> [Double] {
            Array(ArtSchedule(paused: paused).entries(from: Date(timeIntervalSinceReferenceDate: start), mode: .normal).prefix(n))
                .map(\.timeIntervalSinceReferenceDate)
        }
        let base = 813_000_000.0
        for offset in [0.0, 0.004, 0.0133, 0.021, 0.0333, 1.2345] {
            let a = dates(from: base, 200)
            let b = dates(from: base + offset, 200)
            XCTAssertTrue(Set(b).isSubset(of: Set(a + dates(from: a.last!, 200))), "started \(offset) s later, on the same dates")
            XCTAssertGreaterThanOrEqual(b.first!, base + offset, "never before it appeared")
            XCTAssertLessThan(b.first! - (base + offset), 1 / MotionClock.fps, "and within one interval")
        }
        let a = dates(from: base + 0.01, 31)
        XCTAssertEqual(a.last! - a.first!, 1, accuracy: 1e-6, "30 a second")
        XCTAssertEqual(a.first!, (base * 30 + 1) / 30, accuracy: 1e-9)
        XCTAssertTrue(dates(from: base, 5, paused: true).isEmpty, "paused: no updates")
    }

    /// P3 (fix wave 3): the runes and the signs are drawn from symbols laid out once (SwiftUI renders a Canvas symbol
    /// again only when the view changes: its size, the theme, the mode), never from a Text resolved every frame.
    func testTheRunesAndSignsDrawCachedSymbols() throws {
        let motion = try appSource("ThemeMotion.swift")
        let draw = try XCTUnwrap(motion.components(separatedBy: "enum ArtDraw {").last?.components(separatedBy: "/// The page ground per theme").first)
        // D-243 sabotage (I4, I20 survived): a still frame never flares, and the hero's still frame is its still layout.
        XCTAssertTrue(draw.contains("flare: still ? 0 : RuneGlow.flare(i, t), glowTop: RuneGlow.pageGlow, still: still)"),
                      "page runes: no flare when still, the page's glow")
        XCTAssertTrue(draw.contains("flare: still ? 0 : HeroRunes.flare(i, t), glowTop: RuneGlow.heroGlow, still: still)"),
                      "hero runes: no flare when still, the hero's glow")
        XCTAssertTrue(draw.contains("let p = still ? HeroRunes.still(i, in: size) : HeroRunes.pose(i, t, in: size)"),
                      "hero runes: the still layout when still")
        // D-243 (amended on purpose by D-246): the runes (page and hero) draw through `glowingRune`, which resolves the
        // glyph's and its glow's symbols; the ᚠ resolves its own two. None lays out text per frame.
        for name in ["static func runes(", "static func signs(", "static func heroRunes(", "static func watermark(",
                     "private static func glowingRune("] {
            let body = try XCTUnwrap(draw.components(separatedBy: name).last?.components(separatedBy: "\n    }\n").first)
            XCTAssertFalse(body.contains("Text("), "\(name) lays out no text per frame")
        }
        func body(_ name: String) throws -> String {
            try XCTUnwrap(draw.components(separatedBy: name).last?.components(separatedBy: "\n    }\n").first)
        }
        XCTAssertTrue(try body("static func signs(").contains("ctx.resolveSymbol(id: i)"), "the signs draw their symbols")
        XCTAssertTrue(try body("static func runes(").contains("glowingRune(&ctx, p, core: a.core, glow: a.glow, glyph: i, glowTag: pageGlyphGlow + i)"))
        XCTAssertTrue(try body("static func heroRunes(").contains("glowingRune(&inner, p, core: a.core, glow: a.glow, glyph: heroRune + i, glowTag: heroGlyphGlow + i)"))
        let glowing = try body("private static func glowingRune(")
        XCTAssertTrue(glowing.contains("ctx.resolveSymbol(id: glyph)") && glowing.contains("ctx.resolveSymbol(id: glowTag)"),
                      "the glyph and its glow are cached symbols")
        // D-246: the glow is drawn on the glyph's own turned layer (it follows the ink), under the glyph.
        XCTAssertTrue(glowing.contains("""
                if glow > 0, let blurred = ctx.resolveSymbol(id: glowTag) {
                    var under = layer
                    under.opacity = glow
                    under.draw(blurred, at: .zero)
                }
                layer.opacity = core
                layer.draw(symbol, at: .zero)
        """), "the glow turns with its glyph and lies under it")
        let watermark = try body("static func watermark(")
        XCTAssertTrue(watermark.contains("ctx.resolveSymbol(id: fehu)") && watermark.contains("ctx.resolveSymbol(id: fehuGlyphGlow)"))
        XCTAssertTrue(watermark.contains("under.draw(blurred, at: centre)") && watermark.contains("layer.draw(glyph, at: centre)"),
                      "the ᚠ's glow on the ᚠ's centre")
        XCTAssertEqual(motion.components(separatedBy: "draw(Text(").count - 1, 0, "no Text drawn into a canvas")
        let layer = try XCTUnwrap(motion.components(separatedBy: "struct RuneLayer: View {").last?.components(separatedBy: "\n}\n").first)
        XCTAssertTrue(layer.contains("""
                    ArtCanvas(still: 0, symbols: {
                        ForEach(0..<RuneDrift.count, id: \\.self) { ArtDraw.runeSymbol($0) }
                        ForEach(ArtDraw.pageGlyphGlow..<ArtDraw.pageGlyphGlow + RuneDrift.count, id: \\.self) { ArtDraw.runeGlowSymbol($0 - ArtDraw.pageGlyphGlow) }
                    }) { ctx, size, t in
        """), "the page's runes and their glows are the canvas's symbols")
        XCTAssertTrue(layer.contains("""
                    ArtCanvas(still: 0, symbols: {
                        ForEach(ArtDraw.heroRune..<ArtDraw.heroRune + HeroRunes.count, id: \\.self) { ArtDraw.heroRuneSymbol($0 - ArtDraw.heroRune) }
                        ForEach(ArtDraw.heroGlyphGlow..<ArtDraw.heroGlyphGlow + HeroRunes.count, id: \\.self) { ArtDraw.heroGlowSymbol($0 - ArtDraw.heroGlyphGlow) }
                        ArtDraw.fehuSymbol()
                        ArtDraw.fehuGlowSymbol()
                    }) { ctx, size, t in
        """), "the hero's runes, their glows, the ᚠ and its glow are the canvas's symbols")
        // D-243: two ForEach over the same ids in one canvas's symbols trap on launch (CanvasSymbols): each iterates its
        // own tag range, and the ranges do not overlap.
        XCTAssertEqual(layer.components(separatedBy: "ForEach(0..<").count - 1, 1, "only the page's glyphs iterate from 0")
        let ranges = [0..<RuneDrift.count, ArtDraw.pageGlyphGlow..<ArtDraw.pageGlyphGlow + RuneDrift.count,
                      ArtDraw.heroRune..<ArtDraw.heroRune + HeroRunes.count,
                      ArtDraw.heroGlyphGlow..<ArtDraw.heroGlyphGlow + HeroRunes.count,
                      ArtDraw.fehu..<ArtDraw.fehu + 1, ArtDraw.fehuGlyphGlow..<ArtDraw.fehuGlyphGlow + 1]
        for (a, x) in ranges.enumerated() { for y in ranges[(a + 1)...] { XCTAssertFalse(x.overlaps(y), "\(x) and \(y)") } }
        // D-246 (D-243's "a halo is a cached disc", amended on purpose): a glow is GlyphGlow's cached blurred mask of the
        // glyph, never a disc, a blur filter or a shadow.
        let glowSymbol = try body("static func glowSymbol(_ glyph: String, size: CGFloat, colour: Color, tag: Int) -> some View {")
        XCTAssertTrue(glowSymbol.contains("GlyphGlow.view(glyph, size: size, blur: RuneGlow.blur).foregroundColor(colour).tag(tag)"))
        XCTAssertFalse(glowSymbol.contains(".blur(") || glowSymbol.contains("shadow"))
        XCTAssertTrue(motion.contains("ForEach(CurrencyField.floaters.indices, id: \\.self) { ArtDraw.signSymbol($0, width: width) }"))
        XCTAssertTrue(motion.contains("ArtDraw.signs(&ctx, size, t, strength: Strength.of(dark ? \"vardrDarkHeroSigns\" : \"vardrLightHeroSigns\"))"))
        XCTAssertTrue(motion.contains("} symbols: {\n                symbols\n            }"), "the canvas carries its symbols")
        // A sign's size never moves, so one symbol per sign holds: the pose's size is the floater's alone.
        for f in CurrencyField.floaters {
            let sizes = Set((0...40).map { CurrencyField.pose(f, Double($0) * 7.3).size })
            XCTAssertEqual(sizes.count, 1, f.glyph)
        }
        // A rune's glyph, size and colour never move either.
        for i in 0..<RuneDrift.count {
            let still = RuneDrift.still(i, in: CGSize(width: 402, height: 874))
            for t in [0.0, 3.3, 17.9, 101.0] {
                let p = RuneDrift.pose(i, t, in: CGSize(width: 402, height: 874))
                XCTAssertEqual([p.glyph, "\(p.size)", "\(p.silver)"], [still.glyph, "\(still.size)", "\(still.silver)"])
            }
        }
    }

    @MainActor
    func testLowPowerIsReadFromTheSystem() {
        XCTAssertEqual(PowerState().lowPower, ProcessInfo.processInfo.isLowPowerModeEnabled)
    }

    /// The renderer never hands the engine a non-finite time (Curtains.pose traps on one): paused draws the still
    /// frame, and a bad clock falls back to it too.
    func testTheRendererTimeIsAlwaysFinite() {
        XCTAssertEqual(ArtCanvas<EmptyView>.seconds(tick: true, now: 12.5, still: 3), 12.5)
        XCTAssertEqual(ArtCanvas<EmptyView>.seconds(tick: false, now: 12.5, still: 3), 3)
        XCTAssertEqual(ArtCanvas<EmptyView>.seconds(tick: true, now: .nan, still: 3), 3)
        XCTAssertEqual(ArtCanvas<EmptyView>.seconds(tick: true, now: .infinity, still: 3), 3)
        XCTAssertEqual(ArtCanvas<EmptyView>.seconds(tick: false, now: 1, still: .nan), 0)
        // Every curtain pose at the renderer's time is finite.
        for t in [ArtCanvas<EmptyView>.seconds(tick: true, now: Date().timeIntervalSinceReferenceDate, still: 0), 0, 1e9] {
            for i in 0..<3 { XCTAssertTrue(Curtains.pose(i, t).x.isFinite) }
        }
    }

    /// The runes show their still layout exactly when the motion inputs are off, not when only the screen is.
    func testTheRunesAreStillUnderReduceMotionOrLowPowerOnly() {
        XCTAssertTrue(RuneLayer.isStill(reduceMotion: true, lowPower: false))
        XCTAssertTrue(RuneLayer.isStill(reduceMotion: false, lowPower: true))
        XCTAssertFalse(RuneLayer.isStill(reduceMotion: false, lowPower: false))
    }

    /// F6: the bolt's turn rides its own spring (response 0.45, damping 0.55; Android's twin stiffness 195 = (2 pi /
    /// 0.45) squared), scoped to the rotation: the turn's animation comes right after the rotation and before the
    /// press's scale, which keeps its own spring. The call site passes the theme's art.
    func testTheBoltTurnsInTheRaisedAddStyle() throws {
        XCTAssertEqual(AddButtonMotion.turnDegrees, 30)
        XCTAssertEqual(AddButtonMotion.turnResponse, 0.45)
        XCTAssertEqual(AddButtonMotion.turnDamping, 0.55)
        XCTAssertEqual(pow(2 * Double.pi / AddButtonMotion.turnResponse, 2), 195, accuracy: 0.05)
        let bar = try appSource("SkiptiTabBar.swift")
        XCTAssertTrue(bar.contains("RaisedAddStyle(reduceMotion: reduceMotion, art: AddButtonArt.of(AppearanceState.shared.theme))"))
        let rotation = try XCTUnwrap(bar.range(of: ".rotationEffect(.degrees(AddButtonMotion.turn("))
        let turnSpring = try XCTUnwrap(bar.range(of: ".animation(reduceMotion ? nil : .spring(response: AddButtonMotion.turnResponse, dampingFraction: AddButtonMotion.turnDamping),"))
        let scale = try XCTUnwrap(bar.range(of: ".scaleEffect(configuration.isPressed && !reduceMotion ? 0.9 : 1)"))
        let pressSpring = try XCTUnwrap(bar.range(of: ".animation(reduceMotion ? nil : .spring(response: 0.25, dampingFraction: 0.5), value: configuration.isPressed)"))
        XCTAssertTrue(rotation.upperBound < turnSpring.lowerBound && turnSpring.upperBound < scale.lowerBound && scale.upperBound < pressSpring.lowerBound,
                      "rotation, its spring, then the scale and the press's spring")
    }

    // MARK: - Task 5: the heroes and the ground tokens

    /// Every file under ios/Skipti/App, recursively (a Swift file in a subfolder is counted too).
    private func allAppFiles() throws -> [URL] {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let walker = try XCTUnwrap(FileManager.default.enumerator(at: app, includingPropertiesForKeys: nil))
        return walker.compactMap { $0 as? URL }
    }

    private func appFiles() throws -> [URL] { try allAppFiles().filter { $0.pathExtension == "swift" } }

    /// D-214 (the founder, 2026-10-15): faint and accent text sitting directly on the page ground takes the ground
    /// tokens. The counts are pinned after the site-by-site classification recorded in BUILD-LOG D-214: a new site
    /// that sits on the ground must take the token and move its count on purpose.
    func testGroundTextTakesTheGroundTokens() throws {
        XCTAssertTrue(try allAppFiles().contains { $0.path.contains("Assets.xcassets/AccentColor.colorset/") }, "the walk is recursive")
        let files = try appFiles()
        func count(_ needle: String) throws -> Int {
            try files.reduce(0) { n, f in n + (try String(contentsOf: f, encoding: .utf8)).components(separatedBy: needle).count - 1 }
        }
        // D-225: 13, the licence row's chevron went with LicenceRow (Licences draws ListRows on cards).
        // D-225 Task 6: 14, Add expense's "Split" label left its Form section for the page ground.
        // D-225 fix wave (decision 26): 2, every section label takes `textDim` (testEverySectionLabelTakesTextDim); the
        // two left are the expense detail's date and its micro lines on the ground.
        // D-248 (U29.4): 3, Add expense's "0" hint on the page ground.
        XCTAssertEqual(try count("Tokens.groundFaint"), 3)
        // 1 text site on the ground (RemovalFlow's Edit amounts) and `barTint()` (F1, every toolbar item). D-221: You's
        // "Edit" is gone (Q11 a, the progress pill took its place). D-223 (D21): Lately's "See all" sits on the card's
        // surface, so it takes `accent` like every in-card link, not the ground token.
        // D-245: the welcome's Skip, its back chevron (decision 198) and its "Welcome to" lead sit on the ground: 5.
        XCTAssertEqual(try count("Tokens.groundAccent"), 5)
    }

    /// D-225 fix wave (decision 26, review I3, look F4): a section label names what follows and is not faint text, so
    /// every `.sectionLabel()` takes `textDim`, as Android's `SectionLabel` does by default: never `groundFaint` or
    /// `textFaint`, and never left to inherit. The only other colour is `text`, for the labels inside Home's cards
    /// (Lately, Your ledgers and People, Who owes, Recent, Where it went, Settled), `Tok.text` on Android too.
    /// D-225 follow-up (re-review M3): every form is seen, sized (`.sectionLabel(15)`) or not, and a faint colour set
    /// BEFORE the modifier (on its line or the chain lines above it, where the inner style would win) fails too.
    func testEverySectionLabelTakesTextDim() throws {
        var dim = 0, card = 0
        for file in try appFiles() where file.lastPathComponent != "TypeSystem.swift" {
            let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n")
            for (i, line) in lines.enumerated() {
                guard let at = line.range(of: ".sectionLabel("), !line[..<at.lowerBound].contains("//") else { continue }
                // Past the modifier's own argument (a size, possibly with brackets inside).
                var depth = 1
                var end = at.upperBound
                while end < line.endIndex, depth > 0 {
                    if line[end] == "(" { depth += 1 } else if line[end] == ")" { depth -= 1 }
                    end = line.index(after: end)
                }
                // The label's chain: the rest of its line, then the lines that go on with a modifier.
                var chain = String(line[end...])
                for next in lines[(i + 1)...] {
                    let trimmed = next.trimmingCharacters(in: .whitespaces)
                    guard trimmed.hasPrefix(".") else { break }
                    chain += trimmed
                }
                // What comes before it in the same expression: its line, and the lines above while this one goes on.
                var before = String(line[..<at.lowerBound])
                var j = i
                while j > 0, lines[j].trimmingCharacters(in: .whitespaces).hasPrefix(".") {
                    j -= 1
                    before = lines[j] + before
                }
                let site = "\(file.lastPathComponent): \(chain)"
                XCTAssertFalse(chain.contains("groundFaint") || chain.contains("textFaint"), site)
                XCTAssertFalse(before.contains("groundFaint") || before.contains("textFaint"), "set before the label: \(file.lastPathComponent): \(before)")
                if chain.contains(".foregroundStyle(Tokens.textDim)") || chain.contains(".foregroundStyle(Color(Tokens.textDim))") {
                    dim += 1
                } else if chain.contains(".foregroundStyle(Tokens.text)") {
                    card += 1
                } else {
                    XCTFail("a section label takes textDim: \(site)")
                }
            }
        }
        XCTAssertGreaterThanOrEqual(dim, 30, "the walk saw the labels")
        // D-249: 10 with Your spending's two in-card labels and its Expenses label (Home's sections take text).
        XCTAssertEqual(card, 10, "Home's in-card labels")
    }

    /// F10: the art is clipped to the hero's shape, the hero's content is not; the art as a whole is decoration.
    func testTheHeroesDrawBehindTheirContent() throws {
        let motion = try appSource("ThemeMotion.swift")
        let hero = try XCTUnwrap(motion.components(separatedBy: "struct HeroArt: View").last)
        XCTAssertTrue(hero.contains("""
                    }
                }
                .accessibilityHidden(true)
                .allowsHitTesting(false)
            }
        }
        """), "one modifier on the whole per-theme switch")
        let clipped = ".background { HeroArt().clipShape(ThemeArt.cardShape(AppearanceState.shared.theme)) }" // D-220 fix wave (I3)
        for file in ["OverviewScreen.swift", "SignInScreen.swift"] {
            let source = try appSource(file)
            XCTAssertTrue(source.contains(clipped), file)
            XCTAssertEqual(source.components(separatedBy: "HeroArt()").count - 1, 1, file)
            XCTAssertFalse(source.contains("""
            .background { HeroArt() }
            """), "\(file): the art alone is clipped")
        }
    }

    /// F1 (the founder's floor): every toolbar item takes the ground's accent through `.barTint()` (SwiftUI gives a
    /// toolbar item the tint of the place its `.toolbar` is declared), and the bars' own tint is the same token. On iOS
    /// 17 to 25 the back button is set independently of any tint: its title colour and an always-original chevron in
    /// the bar colour, on copies of the bar's appearances (their backgrounds kept). One exception to `.barTint()`:
    /// Profile's Done is white on its own black bar. No other way onto a bar (a bare `.toolbar` control,
    /// `.navigationBarItems`) is allowed to slip past the count.
    @MainActor
    func testBarItemsTakeTheGroundAccent() throws {
        XCTAssertEqual(BarTint.token, "groundAccent")
        let art = try appSource("ThemeArt.swift")
        XCTAssertTrue(art.contains("func barTint() -> some View { tint(Tokens.groundAccent) }"))
        XCTAssertTrue(art.contains("proxy.tintColor = BarTint.color"))
        XCTAssertTrue(art.contains("bar.tintColor = BarTint.color"))
        // R1: iOS 17 to 25, the back button in the bar colour whatever the tint, on the proxy and on every live bar.
        XCTAssertTrue(art.contains("if #unavailable(iOS 26) { BarTint.applyBackButton(to: proxy, proxy: true, theme: state.theme, color: BarTint.color, title: inline, large: large, buttons: buttons) }"))
        XCTAssertTrue(art.contains("if #unavailable(iOS 26) { BarTint.applyBackButton(to: bar, proxy: false, theme: state.theme, color: BarTint.color, title: inline, large: large, buttons: buttons) }"))
        // The styling is made again when the phone flips light and dark under System: the applier (on the app's root,
        // always present) watches the scheme as it watches the stored theme, mode and size.
        let applier = try XCTUnwrap(try appSource("Appearance.swift").components(separatedBy: "struct AppearanceApplier: ViewModifier {").last)
        XCTAssertTrue(applier.contains("@Environment(\\.colorScheme) private var colorScheme"))
        XCTAssertTrue(applier.contains(".onChange(of: colorScheme) { sync() }"))
        XCTAssertTrue(try appSource("SkiptiApp.swift").contains(".appliesStoredAppearance()"))
        let colour = UIColor(red: 0.3, green: 0.38, blue: 0.37, alpha: 1)
        let base = UINavigationBarAppearance()
        base.configureWithOpaqueBackground()
        base.backgroundColor = .red
        let title: [NSAttributedString.Key: Any] = [.foregroundColor: UIColor.green]
        let made = BarTint.backButton(base, color: colour, title: title, large: title, buttons: [:])
        XCTAssertEqual(made.backButtonAppearance.normal.titleTextAttributes[.foregroundColor] as? UIColor, colour)
        XCTAssertEqual(made.backButtonAppearance.highlighted.titleTextAttributes[.foregroundColor] as? UIColor, colour)
        XCTAssertEqual(made.backIndicatorImage.renderingMode, .alwaysOriginal, "no tint recolours the chevron")
        XCTAssertEqual(made.backgroundColor, .red, "the background is the original's")
        XCTAssertEqual(made.titleTextAttributes[.foregroundColor] as? UIColor, .green)
        XCTAssertNil(base.backButtonAppearance.normal.titleTextAttributes[.foregroundColor], "a copy, the original untouched")
        let bar = UINavigationBar()
        bar.standardAppearance = base
        BarTint.applyBackButton(to: bar, proxy: false, theme: .aurora, color: colour, title: title, large: title, buttons: [:])
        for (name, a) in [("standard", bar.standardAppearance), ("scrollEdge", bar.scrollEdgeAppearance),
                          ("compact", bar.compactAppearance), ("compactScrollEdge", bar.compactScrollEdgeAppearance)] {
            XCTAssertEqual(a?.backButtonAppearance.normal.titleTextAttributes[.foregroundColor] as? UIColor, colour, name)
            XCTAssertEqual(a?.backIndicatorImage.renderingMode, .alwaysOriginal, name)
        }
        XCTAssertEqual(bar.standardAppearance.backgroundColor, .red, "the live bar's background kept")
        XCTAssertNil(bar.scrollEdgeAppearance?.backgroundColor, "the top of the page still reads clear")
        // R7: every way onto a bar is counted: ToolbarItem and ToolbarItemGroup take .barTint(); a `.toolbar {` block
        // holds items (HomeShell's `accountItem` is a ToolbarContent of two ToolbarItems); no `.navigationBarItems`.
        var items = 0
        for file in try appFiles() where file.lastPathComponent != "ThemeArt.swift" {
            let source = try String(contentsOf: file, encoding: .utf8)
            let n = source.components(separatedBy: "ToolbarItem(").count - 1 + source.components(separatedBy: "ToolbarItemGroup(").count - 1
            let tinted = source.components(separatedBy: ".barTint()").count - 1
            items += n
            let exempt = file.lastPathComponent == "Profile.swift" ? 1 : 0
            XCTAssertEqual(tinted, n - exempt, "\(file.lastPathComponent): \(n) toolbar items, \(tinted) with .barTint()")
            XCTAssertFalse(source.contains(".navigationBarItems"), "\(file.lastPathComponent): .navigationBarItems bypasses barTint()")
            var rest = Substring(source)
            while let open = rest.range(of: ".toolbar {") {
                var depth = 1
                var i = open.upperBound
                while depth > 0, i < rest.endIndex {
                    if rest[i] == "{" { depth += 1 } else if rest[i] == "}" { depth -= 1 }
                    i = rest.index(after: i)
                }
                let block = rest[open.upperBound..<i]
                XCTAssertTrue(block.contains("ToolbarItem") || block.trimmingCharacters(in: .whitespacesAndNewlines) == "accountItem }",
                              "\(file.lastPathComponent): a .toolbar block without a ToolbarItem: \(block.prefix(80))")
                rest = rest[i...]
            }
        }
        // D-217 final fix: 21, the two Join sheets' Cancels are now one conditional item in JoinScreen.
        // D-249: 22, Your spending's Export Just me.
        XCTAssertEqual(items, 22, "a new toolbar item takes .barTint() and moves this count on purpose")
    }

    /// D-224 (E3): the done moments' motion, goldens re-typed from .drafts/d224/motion.py part 6 (never read back from
    /// the code): the shared CSS cubic-bezier at nine points, the check's pose at 14 times, the toast's at ten times
    /// moving and still.
    func testTheDoneMotionMatchesTheGoldens() {
        let ease: [((Double, Double, Double, Double), Double, Double)] = [
            ((0.6, 0.0, 0.2, 1.0), 0.25, 0.095833), ((0.6, 0.0, 0.2, 1.0), 0.5, 0.704685), ((0.6, 0.0, 0.2, 1.0), 0.75, 0.956227),
            ((0.3, 0.0, 0.2, 1.0), 0.25, 0.348935), ((0.3, 0.0, 0.2, 1.0), 0.5, 0.797153), ((0.3, 0.0, 0.2, 1.0), 0.75, 0.960733),
            ((0.3, 1.6, 0.5, 1.0), 0.25, 0.914187), ((0.3, 1.6, 0.5, 1.0), 0.5, 1.110044), ((0.3, 1.6, 0.5, 1.0), 0.75, 1.044118),
        ]
        for (p, u, y) in ease { near(Bezier.y(u, p), y, 1e-5, "bezier \(p) at \(u)") }
        // The three curves the check uses are the ones the goldens name.
        XCTAssertTrue(CheckMotion.circleEase == (0.6, 0.0, 0.2, 1.0))
        XCTAssertTrue(CheckMotion.tickEase == (0.3, 0.0, 0.2, 1.0))
        XCTAssertTrue(CheckMotion.popEase == (0.3, 1.6, 0.5, 1.0))
        XCTAssertTrue(ToastMotion.ease == (0.2, 0.7, 0.3, 1.0))
        let check: [(Double, (Double, Double, Double, Double))] = [
            (0.0, (0.0, 0.0, 1.0, 0.0)), (0.2, (0.300932, 0.0, 1.0, 5.1429)), (0.4, (0.947369, 0.0, 1.0, 10.2857)),
            (0.5, (0.996614, 0.108372, 1.0, 12.8571)), (0.55, (1.0, 0.453045, 1.0, 14.1429)),
            (0.6, (1.0, 0.733659, 1.0, 15.4286)), (0.7, (1.0, 0.979698, 1.0, 18.0)), (0.8, (1.0, 1.0, 1.087172, 20.5714)),
            (0.95, (1.0, 1.0, 1.08, 24.4286)), (1.1, (1.0, 1.0, 0.992616, 28.2857)), (1.2, (1.0, 1.0, 1.0, 30.8571)),
            (3.5, (1.0, 1.0, 1.0, 90.0)), (13.99, (1.0, 1.0, 1.0, 359.7429)), (14.0, (1.0, 1.0, 1.0, 0.0)),
        ]
        XCTAssertEqual(check.count, 14)
        for (t, want) in check {
            let pose = CheckMotion.pose(t)
            near(pose.circle, want.0, 1e-5, "circle at \(t)")
            near(pose.tick, want.1, 1e-5, "tick at \(t)")
            near(pose.scale, want.2, 1e-5, "scale at \(t)")
            near(pose.degrees, want.3, 1e-3, "degrees at \(t)")
        }
        // The lengths the dashes are measured against: 2 pi 48 and the tick's two legs (motion.py: 301.5929, 58.0023).
        near(CheckMotion.circleLength, 301.5929, 1e-4, "circle length")
        near(CheckMotion.tickLength, 58.0023, 1e-4, "tick length")
        let toast: [(Double, (Double, Double)?, (Double, Double)?)] = [
            (-0.1, nil, nil), (0.0, (0.0, 10.0), (1.0, 0.0)), (0.05, (0.676225, 3.237752), (1.0, 0.0)),
            (0.1, (0.909972, 0.900279), (1.0, 0.0)), (0.2, (1.0, 0.0), (1.0, 0.0)), (1.5, (1.0, 0.0), (1.0, 0.0)),
            (2.75, (1.0, 0.0), (1.0, 0.0)), (2.875, (0.5, 0.0), (1.0, 0.0)), (2.99, (0.04, 0.0), (1.0, 0.0)),
            (3.0, nil, nil),
        ]
        XCTAssertEqual(toast.count, 10)
        for (t, moving, still) in toast {
            for (isStill, want) in [(false, moving), (true, still)] {
                let pose = ToastMotion.pose(elapsed: t, still: isStill)
                guard let want else {
                    XCTAssertNil(pose.map { [$0.opacity, $0.dy] }, "toast at \(t) still \(isStill) is gone")
                    continue
                }
                guard let pose else { XCTFail("toast at \(t) still \(isStill) missing"); continue }
                near(pose.opacity, want.0, 1e-5, "toast opacity at \(t) still \(isStill)")
                near(pose.dy, want.1, 1e-5, "toast dy at \(t) still \(isStill)")
            }
        }
    }

    /// D-224: the check's still frame (Reduce Motion or Low Power) is its end state: drawn whole, unpopped, upright; and
    /// a moment's clock is the art's while it ticks, else the still frame's time.
    func testTheStillFrameIsTheEndState() {
        XCTAssertTrue(CheckMotion.pose(CheckMotion.stillElapsed) == (1, 1, 1, 0))
        XCTAssertEqual(CheckMotion.stillElapsed, CheckMotion.runeTurn, "the ring a whole turn round")
        XCTAssertEqual(MomentClock.elapsed(tick: true, now: 105.5, startedAt: 100, still: 14), 5.5)
        XCTAssertEqual(MomentClock.elapsed(tick: false, now: 105.5, startedAt: 100, still: 14), 14, "not ticking: the still frame")
        XCTAssertEqual(MomentClock.elapsed(tick: true, now: .nan, startedAt: 100, still: 14), 14, "a non-finite clock: the still frame")
        XCTAssertEqual(MomentClock.elapsed(tick: true, now: .infinity, startedAt: 100, still: 14), 14)
    }

    /// D-221 (L45): the settled burst, goldens from .drafts/d221/motion.py part 5 (the Look Book's `celebrate`).
    func testTheBurstMatchesTheGoldens() {
        let easing: [(Double, Double)] = [(0.1, 0.332602), (0.25, 0.676225), (0.5, 0.909972), (0.75, 0.983712), (0.9, 0.997765)]
        for (u, y) in easing { near(SettledBurst.eased(u), y, 1e-5, "ease \(u)") }
        XCTAssertEqual(SettledBurst.eased(0), 0)
        XCTAssertEqual(SettledBurst.eased(1), 1)
        let poses: [(Int, Double, (Double, Double, Double, Double))] = [
            (0, 0.0, (0.0, 0.0, 0.4, 0.0)),
            (0, 0.3, (102.4933, 2.2781, 0.984133, 0.920664)),
            (0, 0.75, (120.5984, 22.9696, 0.840012, 0.200062)),
            (0, 1.2, (125.0735, 28.084, 0.80439, 0.02195)),
            (0, 1.49, (125.6245, 28.7137, 0.800004, 2e-05)),
            (7, 0.5, (-19.9155, 173.9785, 0.931106, 0.655532)),
            (13, 0.9, (-216.0501, 24.55, 0.829144, 0.145721)),
            (25, 1.0, (119.5441, -3.6279, 0.819883, 0.099414)),
            (3, 1.85, (166.5731, 176.2784, 0.800003, 1.3e-05)),
        ]
        for (i, t, g) in poses {
            guard let p = SettledBurst.pose(i, elapsed: t, width: 402) else { XCTFail("particle \(i) at \(t) is nil"); continue }
            near(p.dx, g.0, 1e-3, "particle \(i) at \(t) dx")
            near(p.dy, g.1, 1e-3, "particle \(i) at \(t) dy")
            near(p.scale, g.2, 1e-3, "particle \(i) at \(t) scale")
            near(p.opacity, g.3, 1e-3, "particle \(i) at \(t) opacity")
        }
        XCTAssertNil(SettledBurst.pose(3, elapsed: 1.86, width: 402), "particle 3 has finished at 1.86 s")
        XCTAssertEqual(SettledBurst.count, 26)
        XCTAssertEqual(SettledBurst.particleDuration(0), 1.5)
        near(SettledBurst.particleDuration(3), 1.86, 1e-12)
        XCTAssertEqual([SettledBurst.originX, SettledBurst.originY], [0.5, 0.46])
        XCTAssertEqual([SettledBurst.auroraDot, SettledBurst.auroraGlow, SettledBurst.norseRune, SettledBurst.vardrSquare],
                       [8, 10, 22, 4], "motion.py part 5: the Look Book's particles x 1.3")
    }

    /// D-221 (L45): the celebration's timeline (motion.py part 5).
    func testCelebrationTiming() {
        XCTAssertEqual(CelebrationTiming.total, 3.6)
        XCTAssertEqual(CelebrationTiming.dimTo, 0.22)
        XCTAssertEqual(CelebrationTiming.dimIn, 0.3)
        XCTAssertEqual(CelebrationTiming.messageIn, 0.4)
        XCTAssertEqual(CelebrationTiming.messageRise, 10)
        XCTAssertEqual(CelebrationTiming.messageTop, 0.42)
    }

    /// D-221 Task 8 (L45): the burst draws each theme's particle at the engine's pose, in `ring`: Aurora a dot with a
    /// glow (a radial gradient of ring at 0.55 to clear, Android's), Norse a rune of the Futhark from a symbol laid out
    /// once (P3), Vardr Labs a square. Nothing before the play's start or with a clock that is not finite.
    func testTheBurstDrawsEachThemesParticle() throws {
        let motion = try appSource("ThemeMotion.swift")
        let burst = try XCTUnwrap(motion.components(separatedBy: "// MARK: - D-221 Task 8: the settled burst").dropFirst().first,
                                  "the burst's section")
        for anchor in [
            "guard elapsed.isFinite, elapsed >= 0 else { return }",
            "let origin = CGPoint(x: size.width * SettledBurst.originX, y: size.height * SettledBurst.originY)",
            "for i in 0..<SettledBurst.count {",
            "guard let p = SettledBurst.pose(i, elapsed: elapsed, width: Double(size.width)), p.opacity > 0 else { continue }",
            "layer.opacity = p.opacity",
            "case .aurora: burstDot(&layer, at, p.scale)",
            "case .norse: burstRune(&layer, i, at, p.scale)",
            "case .vardr: burstSquare(&layer, at, p.scale)",
            "let dot = SettledBurst.auroraDot / 2 * scale",
            "let glow = dot + SettledBurst.auroraGlow * scale",
            "Gradient(colors: [Tokens.ring.opacity(burstGlowAlpha), Tokens.ring.opacity(0)])",
            "static let burstGlowAlpha = 0.55",
            "guard let glyph = ctx.resolveSymbol(id: i % Runes.futhark.count) else { return }",
            "Text(verbatim: Runes.futhark[i]).font(TypeRole.rune(SettledBurst.norseRune)).foregroundColor(Tokens.ring).tag(i)",
            "if theme == .norse { ForEach(Runes.futhark.indices, id: \\.self) { burstRuneSymbol($0) } }",
            "let side = SettledBurst.vardrSquare * scale",
        ] {
            XCTAssertEqual(burst.components(separatedBy: anchor).count - 1, 1, anchor)
        }
        XCTAssertFalse(burst.contains("Text(verbatim: Runes.futhark[i % "), "a rune is laid out once, not per particle")
        XCTAssertEqual(Runes.futhark.count, 24)
    }

    /// D-221 Task 8: the burst, drawn: nothing at the origin once the particles have left it, ink where particle 0's
    /// pose puts it, nothing before the play started and nothing once every particle has finished (1.86 s).
    @MainActor
    func testTheBurstDrawsWhereThePoseSays() throws {
        let size = CGSize(width: 402, height: 874)
        func alpha(_ theme: AppTheme, _ elapsed: Double, at point: CGPoint) throws -> CGFloat {
            let view = Canvas { ctx, size in ArtDraw.burst(&ctx, size, elapsed, theme: theme) }.frame(width: size.width, height: size.height)
            let renderer = ImageRenderer(content: view)
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.cgImage)
            // Redrawn into a known RGBA8 bitmap, whatever the renderer's own format.
            var pixels = [UInt8](repeating: 0, count: Int(size.width) * Int(size.height) * 4)
            let drawn = pixels.withUnsafeMutableBytes { raw -> Bool in
                guard let ctx = CGContext(data: raw.baseAddress, width: Int(size.width), height: Int(size.height), bitsPerComponent: 8,
                                          bytesPerRow: Int(size.width) * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
                ctx.draw(image, in: CGRect(origin: .zero, size: size))
                return true
            }
            XCTAssertTrue(drawn)
            // CGContext's origin is bottom left: row 0 of the buffer is the image's top row.
            let x = Int(point.x.rounded()), y = Int(point.y.rounded())
            return CGFloat(pixels[(y * Int(size.width) + x) * 4 + 3]) / 255
        }
        let origin = CGPoint(x: size.width * SettledBurst.originX, y: size.height * SettledBurst.originY)
        let p = try XCTUnwrap(SettledBurst.pose(0, elapsed: 0.3, width: size.width))
        let at = CGPoint(x: origin.x + p.dx, y: origin.y + p.dy)
        for theme in [AppTheme.aurora, .vardr] {
            XCTAssertGreaterThan(try alpha(theme, 0.3, at: at), 0.5, "\(theme): particle 0 at its pose")
            XCTAssertEqual(try alpha(theme, 0.3, at: origin), 0, "\(theme): the origin is empty at 0.3 s")
            XCTAssertEqual(try alpha(theme, -0.5, at: origin), 0, "\(theme): nothing before the start")
            XCTAssertEqual(try alpha(theme, 0.3 - 1e9, at: at), 0, "\(theme): a canvas still at 0 draws nothing")
            XCTAssertEqual(try alpha(theme, 1.86, at: at), 0, "\(theme): every particle has finished")
            XCTAssertEqual(try alpha(theme, .nan, at: at), 0, "\(theme): a clock that is not finite draws nothing")
        }
    }

    // MARK: - D-231 Task 1: the Less motion setting (decisions 68 to 70); Android's twin vectors are in ThemeMotionTest

    /// Nothing stored reads as the low-memory default; a stored value, either way, wins.
    func testTheLessMotionSettingParses() {
        XCTAssertEqual(MotionSetting.storageKey, "appearance.lessMotion")
        XCTAssertFalse(MotionSetting.parse(stored: nil, lowRam: false), "never set, memory to spare: off")
        XCTAssertTrue(MotionSetting.parse(stored: nil, lowRam: true), "never set, a low-memory phone: on")
        XCTAssertTrue(MotionSetting.parse(stored: true, lowRam: false), "stored on")
        XCTAssertFalse(MotionSetting.parse(stored: false, lowRam: true), "a stored off beats the low-memory default")
    }

    /// Under 3 GiB, or the platform's own flag. 3 GiB is re-derived here as 3 x 1024^3 = 3_221_225_472, not read back.
    func testALowMemoryPhoneIsUnderThreeGiB() {
        let threeGiB: UInt64 = 3_221_225_472
        XCTAssertEqual(MotionSetting.lowRamBytes, threeGiB)
        XCTAssertTrue(MotionSetting.lowRam(totalBytes: threeGiB - 1, lowRamFlag: false), "one byte under")
        XCTAssertFalse(MotionSetting.lowRam(totalBytes: threeGiB, lowRamFlag: false), "exactly 3 GiB is not low")
        XCTAssertFalse(MotionSetting.lowRam(totalBytes: 6_442_450_944, lowRamFlag: false), "6 GiB")
        XCTAssertTrue(MotionSetting.lowRam(totalBytes: 2_147_483_648, lowRamFlag: false), "2 GiB")
        XCTAssertTrue(MotionSetting.lowRam(totalBytes: 8_589_934_592, lowRamFlag: true), "the platform's flag wins at 8 GiB")
        XCTAssertEqual(MotionSetting.lowRamAtLaunch,
                       MotionSetting.lowRam(totalBytes: ProcessInfo.processInfo.physicalMemory, lowRamFlag: false),
                       "iOS has no low-RAM flag: memory alone")
    }

    /// The switch only adds stillness: the four rows of OR.
    func testLessMotionOnlyAddsStillness() {
        XCTAssertFalse(MotionSetting.reduced(system: false, lessMotion: false), "both off: motion")
        XCTAssertTrue(MotionSetting.reduced(system: false, lessMotion: true), "the switch alone")
        XCTAssertTrue(MotionSetting.reduced(system: true, lessMotion: false), "the phone's Reduce Motion, switch off")
        XCTAssertTrue(MotionSetting.reduced(system: true, lessMotion: true), "both on")
    }

    /// The row's words, verbatim (Android's ThemeMotionTest reads this file's `MotionCopy` and compares).
    func testTheLessMotionCopy() {
        XCTAssertEqual(MotionCopy.title, "Less motion")
        XCTAssertEqual(MotionCopy.summary, "Still art, fewer animations")
        for s in [MotionCopy.title, MotionCopy.summary] {
            XCTAssertFalse(s.contains("\u{2014}") || s.contains("\u{2013}"), "no dash in \(s)")
        }
    }

    // MARK: - D-231 Task 2: one door (decision 71)

    /// AppearanceState loads the switch: a stored value wins; nothing stored is the low-memory default.
    func testAppearanceStateLoadsLessMotion() throws {
        let name = "d231-less-motion-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        XCTAssertFalse(AppearanceState.stored(defaults, lowRam: false).lessMotion, "nothing stored, memory to spare: off")
        XCTAssertTrue(AppearanceState.stored(defaults, lowRam: true).lessMotion, "nothing stored, low memory: on")
        defaults.set(true, forKey: MotionSetting.storageKey)
        XCTAssertTrue(AppearanceState.stored(defaults, lowRam: false).lessMotion, "stored on")
        defaults.set(false, forKey: MotionSetting.storageKey)
        XCTAssertFalse(AppearanceState.stored(defaults, lowRam: true).lessMotion, "a stored off beats the low-memory default")
    }

    /// The root applier hands every view `\.motionReduced`: the phone's Reduce Motion OR the stored switch, so the switch
    /// alone holds the app still and the phone's own setting still does whatever the switch says.
    @MainActor
    func testTheRootHandsLessMotionToEveryView() throws {
        let defaults = UserDefaults.standard
        let saved = defaults.object(forKey: MotionSetting.storageKey)
        defer {
            if let saved { defaults.set(saved, forKey: MotionSetting.storageKey) } else { defaults.removeObject(forKey: MotionSetting.storageKey) }
        }
        func seen(stored: Bool, system: Bool) -> Bool? {
            defaults.set(stored, forKey: MotionSetting.storageKey)
            let box = MotionSeen()
            let host = UIHostingController(rootView: MotionProbe(box: box).appliesStoredAppearance()
                .environment(\._accessibilityReduceMotion, system))
            _ = host.sizeThatFits(in: CGSize(width: 10, height: 10))
            return box.value
        }
        XCTAssertEqual(seen(stored: false, system: false), false, "both off: motion")
        XCTAssertEqual(seen(stored: true, system: false), true, "Less motion alone holds the app still")
        XCTAssertEqual(seen(stored: false, system: true), true, "the phone's Reduce Motion still holds it, switch off")
        XCTAssertEqual(seen(stored: true, system: true), true, "both on")
        // The applier keeps the live state in step and watches the key.
        let applier = try XCTUnwrap(try appSource("Appearance.swift").components(separatedBy: "struct AppearanceApplier: ViewModifier {").last)
        XCTAssertTrue(applier.contains(".onChange(of: lessMotionStored) { sync() }"))
        XCTAssertTrue(applier.contains("        state.lessMotion = lessMotion\n"))
    }

    /// Outside a view (a done moment posted from code) the motion input is the phone's flag read now OR the switch.
    @MainActor
    func testReducedNowReadsTheSwitch() throws {
        let state = AppearanceState.shared
        let saved = state.lessMotion
        defer { state.lessMotion = saved }
        state.lessMotion = true
        XCTAssertTrue(MotionSetting.reducedNow(), "the switch alone")
        state.lessMotion = false
        XCTAssertEqual(MotionSetting.reducedNow(), UIAccessibility.isReduceMotionEnabled, "off: the phone's own flag")
        XCTAssertTrue(try appSource("DoneMoments.swift").contains(
            "!MotionClock.momentTicks(reduceMotion: MotionSetting.reducedNow(), active: true)"),
                      "the done moments' imperative read goes through the door")
    }

    /// Decision 71: one door. The phone's Reduce Motion is read in two places only: the root applier (Appearance.swift)
    /// and `MotionSetting.reducedNow()` (ThemeMotion.swift). Every view that honours it reads `\.motionReduced`.
    func testReduceMotionHasOneDoor() throws {
        var system: [String: Int] = [:], imperative: [String: Int] = [:], readers: [String: Int] = [:]
        for file in try appFiles() {
            let text = try String(contentsOf: file, encoding: .utf8)
            let name = file.lastPathComponent
            let a = text.components(separatedBy: "accessibilityReduceMotion").count - 1
            let b = text.components(separatedBy: "isReduceMotionEnabled").count - 1
            let c = text.components(separatedBy: "@Environment(\\.motionReduced) private var reduceMotion").count - 1
            if a > 0 { system[name] = a }
            if b > 0 { imperative[name] = b }
            if c > 0 { readers[name] = c }
        }
        XCTAssertEqual(system, ["Appearance.swift": 1], "the environment's Reduce Motion is read by the root applier alone")
        XCTAssertEqual(imperative, ["ThemeMotion.swift": 1], "the imperative flag is read by MotionSetting.reducedNow() alone")
        // D-234 (A2, decision 80): the App icon rows open through the door too (AccountHubScreen's AppIconSection).
        // D-243 (amended on purpose): ThemeMotion.swift's third reader is `ArtTurn` (You's turning ring), beside ArtCanvas
        // and RuneLayer.
        // D-250 (amended on purpose): ThemeMotion.swift's readers 4 to 7 are the change helpers (RowsMotion, Reveal,
        // MoneyChange, FillMotion); every sweep site moves through them, so no other file gained a reader.
        XCTAssertEqual(readers, ["AccountHubScreen.swift": 1, "DoneMoments.swift": 4, "HomeShell.swift": 1, "ListRows.swift": 1,
                                 "OverviewScreen.swift": 1, "Settled.swift": 1, "SkiptiTabBar.swift": 2, "ThemeMotion.swift": 7,
                                 // D-245: the welcome's step change and its progress (a moment) read the door.
                                 "Welcome.swift": 2],
                       "every reader reads the door")
        let applier = try XCTUnwrap(try appSource("Appearance.swift").components(separatedBy: "struct AppearanceApplier: ViewModifier {").last)
        XCTAssertTrue(applier.contains("@Environment(\\.accessibilityReduceMotion) private var systemReduceMotion"))
        XCTAssertTrue(applier.contains(".environment(\\.motionReduced, MotionSetting.reduced(system: systemReduceMotion, lessMotion: lessMotion))"))
    }

    // MARK: - D-243: livelier Norse art (goldens retyped from .drafts/d243/motion-after.out, never read from the code)

    /// D-243 (plan test 1): a rune flares now and then: 0 at rest, eased up from 0.62 of its cycle to 1 at 0.76, eased
    /// back to 0 at 0.90 (each half CSS ease-in-out, as the mock-up's keyframes), never outside 0...1. Page rune i flares
    /// on a 7 + (i x 5) % 7 s cycle led by (i x 3) % 11 s. Negatives: just before the fall it still glows; at 0.90 it does
    /// not; only the top reaches 1.
    func testTheRunesFlareAsTheMockUpDoes() {
        XCTAssertEqual((0..<16).map(RuneGlow.cycle), [7, 12, 10, 8, 13, 11, 9, 7, 12, 10, 8, 13, 11, 9, 7, 12])
        XCTAssertEqual((0..<16).map(RuneGlow.lead), [0, 3, 6, 9, 1, 4, 7, 10, 2, 5, 8, 0, 3, 6, 9, 1])
        XCTAssertEqual(RuneGlow.rise, 0.62)
        XCTAssertEqual(RuneGlow.top, 0.76)
        XCTAssertEqual(RuneGlow.fall, 0.90)
        XCTAssertTrue(RuneGlow.ease == (0.42, 0.0, 0.58, 1.0))
        let shape: [(Double, Double)] = [(0.0, 0), (0.61, 0), (0.62, 0), (0.65, 0.094109), (0.69, 0.5), (0.76, 1), (0.83, 0.5),
                                         (0.87, 0.094109), (0.899, 0.000097), (0.90, 0), (0.95, 0)]
        for (q, g) in shape { near(RuneGlow.shape(q), g, 1e-6, "shape at \(q)") }
        XCTAssertGreaterThan(RuneGlow.shape(0.899), 0, "it still glows just before the fall")
        XCTAssertEqual(RuneGlow.shape(0.90), 0, "and is at rest from the fall on")
        XCTAssertEqual(RuneGlow.shape(0.76), 1)
        let flare: [(Int, Double, Double)] = [
            (0, 4.27, 0), (0, 4.34, 0), (0, 4.83, 0.5), (0, 5.32, 1), (0, 5.81, 0.5), (0, 6.293, 0.000097), (0, 6.3, 0),
            (5, 3.59, 0.5), (15, 7.28, 0.5), (0, 813_000_000.25, 0), (0, 813_000_003.9, 0.621569), (3, 100, 0.002449),
            (11, 12.5, 0),
        ]
        for (i, t, g) in flare { near(RuneGlow.flare(i, t), g, 1e-6, "rune \(i) at \(t)") }
        var tops = 0
        for i in 0..<16 {
            for step in 0..<1200 {
                let g = RuneGlow.flare(i, Double(step) * 0.05)
                XCTAssertTrue(g >= 0 && g <= 1, "rune \(i) flare in 0...1")
                if g == 1 { tops += 1 }
            }
        }
        for step in 0..<1000 {
            let q = Double(step) / 1000
            if q < 0.62 || q >= 0.90 { XCTAssertEqual(RuneGlow.shape(q), 0, "at rest at \(q)") }
            if q != 0.76 { XCTAssertLessThan(RuneGlow.shape(q), 1, "only the top is 1 (\(q))") }
        }
        XCTAssertGreaterThan(tops, 0)
    }

    /// D-246 (decision 195, the founder: "make the actual rune glow"; plan: "no disc drawing left"): no rune glow draws a
    /// circle, an ellipse or a radial gradient: not the canvas's rune and ᚠ symbols and renderers, not GlyphGlow, not
    /// the ring's lit runes. And the glow masks are built only where symbols are made (once), never by a per-frame draw.
    /// The negative: the guard's own detector catches D-243's disc halo, word for word.
    func testNoRuneGlowDrawsADisc() throws {
        func drawsADisc(_ code: String) -> Bool {
            ["Circle(", "Ellipse(", "ellipseIn", "RadialGradient", "radialGradient", ".addEllipse(", "addArc("].contains { code.contains($0) }
        }
        XCTAssertTrue(drawsADisc("""
                Circle()
                    .fill(RadialGradient(colors: [colour, colour.opacity(0)], center: .center, startRadius: 0, endRadius: radius))
        """), "the detector sees D-243's halo")
        XCTAssertFalse(drawsADisc("GlyphGlow.view(glyph, size: size, blur: RuneGlow.blur).foregroundColor(colour).tag(tag)"))
        let motion = try appSource("ThemeMotion.swift")
        let draw = try XCTUnwrap(motion.components(separatedBy: "enum ArtDraw {").last?.components(separatedBy: "/// The page ground per theme").first)
        let runeParts = try XCTUnwrap(draw.components(separatedBy: "/// D-243: symbol tags.").last?
            .components(separatedBy: "/// Sign i of the currency field as a symbol").first)
        XCTAssertTrue(runeParts.contains("static func glowingRune(") && runeParts.contains("static func watermark("),
                      "the slice holds the rune renderers")
        XCTAssertFalse(drawsADisc(runeParts), "no disc in the runes' symbols or renderers")
        let glyphGlow = try motionSection("enum GlyphGlow {", to: "\n}\n")
        XCTAssertFalse(drawsADisc(glyphGlow), "GlyphGlow draws the glyph, never a disc")
        XCTAssertTrue(glyphGlow.contains("CTFontCreatePathForGlyph(runFont, glyph, &at)"), "it fills the glyph's own outline")
        let layer = try motionSection("struct RuneLayer: View {", to: "\n}\n")
        XCTAssertFalse(drawsADisc(layer))
        let ring = try XCTUnwrap(try appSource("YouProgress.swift").components(separatedBy: "extension RuneRing: View {").last?
            .components(separatedBy: "\n}\n").first)
        XCTAssertFalse(drawsADisc(ring), "the ring's lit runes glow from their glyphs")
        XCTAssertFalse(motion.contains("RingTurn.litHalo") || motion.contains("RuneGlow.halo"), "the disc's values are gone")
        // Built once: only the symbol makers ask GlyphGlow; the per-frame renderers draw resolved symbols.
        func body(_ name: String) throws -> String {
            try XCTUnwrap(draw.components(separatedBy: name).last?.components(separatedBy: "\n    }\n").first)
        }
        for name in ["static func runes(", "static func heroRunes(", "static func watermark(", "private static func glowingRune("] {
            XCTAssertFalse(try body(name).contains("GlyphGlow."), "\(name) builds no mask per frame")
        }
        XCTAssertEqual(draw.components(separatedBy: "GlyphGlow.").count - 1, 1, "one maker: glowSymbol")
    }

    /// D-246: GlyphGlow builds each mask once a process: a second ask, or a size in the same whole-point bucket, builds
    /// nothing (the counter); a new size or blur builds one (the negative). The mask is the glyph's Text box plus 4 sigma a
    /// side, and its blurred peak is motion.py 5a's for every Futhark glyph (it measures the same Gaussian of the same
    /// outline), so the floors' model (the peak under the full core) is the shell's.
    func testGlyphGlowIsBuiltOnceAndIsMotionPysMask() {
        let before = GlyphGlow.builds
        let a = GlyphGlow.mask("\u{16DE}", size: 41, blur: RuneGlow.blur)
        XCTAssertEqual(GlyphGlow.builds, before + 1, "the first ask builds it")
        let b = GlyphGlow.mask("\u{16DE}", size: 41, blur: RuneGlow.blur)
        _ = GlyphGlow.mask("\u{16DE}", size: 40.7, blur: RuneGlow.blur)
        for _ in 0..<50 { _ = GlyphGlow.view("\u{16DE}", size: 41, blur: RuneGlow.blur) }
        XCTAssertEqual(GlyphGlow.builds, before + 1, "never again, in the same bucket too")
        XCTAssertTrue(a.image === b.image, "the cached image itself")
        _ = GlyphGlow.mask("\u{16DE}", size: 42, blur: RuneGlow.blur)
        XCTAssertEqual(GlyphGlow.builds, before + 2, "a new size builds one")
        _ = GlyphGlow.mask("\u{16DE}", size: 42, blur: 0.12)
        XCTAssertEqual(GlyphGlow.builds, before + 3, "a new blur builds one")
        // The box: the Text box (advance by ascender + descender, 1.15 em) plus 4 sigma a side, to a whole pixel.
        let sigma = 0.18 * 41
        XCTAssertGreaterThanOrEqual(Double(a.size.height), 1.15 * 41 + 8 * sigma - 1e-9)
        XCTAssertLessThan(Double(a.size.height), 1.15 * 41 + 8 * sigma + 1 / Double(a.scale) + 1e-9)
        XCTAssertGreaterThanOrEqual(Double(a.size.width), 0.62 * 41 + 8 * sigma - 1e-9, "U+16DE's advance is 0.62 em")
        XCTAssertEqual(Double(a.scale), min(3, 6 / sigma), accuracy: 1e-9, "six pixels a sigma")
        // motion.py 5a's peaks (blur 0.18), each glyph at a page rune's size: motion.py's mask (within 0.01).
        for (glyph, peak) in GlyphGlowGoldens.peaks {
            let m = GlyphGlow.mask(glyph, size: 20, blur: RuneGlow.blur)
            XCTAssertEqual(m.peak, peak, accuracy: 0.01, "U+\(String(glyph.unicodeScalars.first!.value, radix: 16, uppercase: true))'s peak")
        }
        // D-246 fix wave (M1): the floors (AppearanceTests) take motion.py's layer peak, so the link to the masks the shell
        // DRAWS is one-sided and tight: every mask a layer draws, at the size it draws it, peaks at most motion.py's layer
        // peak + 0.002 (the 8-bit image rounds within 0.002 of it). A two-sided 0.01 let a page peak of 0.353 through.
        var pagePeak = 0.0, heroPeak = 0.0
        for i in 0..<RuneDrift.count {
            let p = RuneDrift.still(i, in: .zero)
            let peak = GlyphGlow.mask(p.glyph, size: p.size, blur: RuneGlow.blur).peak
            XCTAssertLessThanOrEqual(peak, GlyphGlowGoldens.page + 0.002, "page rune \(i) at \(p.size)")
            pagePeak = max(pagePeak, peak)
        }
        for (i, s) in HeroRunes.specs.enumerated() {
            let peak = GlyphGlow.mask(s.glyph, size: s.size, blur: RuneGlow.blur).peak
            XCTAssertLessThanOrEqual(peak, GlyphGlowGoldens.hero + 0.002, "hero row \(i) at \(s.size)")
            heroPeak = max(heroPeak, peak)
        }
        let fehuPeak = GlyphGlow.mask(Runes.balance, size: ThemeArt.m("heroWatermark"), blur: RuneGlow.blur).peak
        XCTAssertLessThanOrEqual(fehuPeak, GlyphGlowGoldens.fehu + 0.002, "the ᚠ at 118")
        XCTAssertGreaterThan(pagePeak, GlyphGlowGoldens.page - 0.01, "the page's masks are motion.py's")
        XCTAssertGreaterThan(heroPeak, GlyphGlowGoldens.hero - 0.01, "the hero's masks are motion.py's")
        XCTAssertGreaterThan(fehuPeak, GlyphGlowGoldens.fehu - 0.01, "the ᚠ's mask is motion.py's")
    }

    /// D-246: the glow sits on the ink. The mask's centre of mass (the blur keeps it) lands on the centre of mass of the
    /// glyph as SwiftUI lays its Text out, measured from each box's centre, for the ᚠ at 118 and a page rune at 27.
    @MainActor
    func testTheGlowSitsOnTheGlyphsInk() throws {
        func centroid(_ image: CGImage, scale: CGFloat) -> (x: Double, y: Double) {
            let w = image.width, h = image.height
            var px = [UInt8](repeating: 0, count: w * h * 4)
            px.withUnsafeMutableBytes { raw in
                let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 4 * w,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
                ctx?.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            }
            var sx = 0.0, sy = 0.0, total = 0.0
            for y in 0..<h { for x in 0..<w {
                let a = Double(px[4 * (y * w + x) + 3])
                sx += a * (Double(x) + 0.5); sy += a * (Double(y) + 0.5); total += a
            } }
            return ((sx / total - Double(w) / 2) / Double(scale), (sy / total - Double(h) / 2) / Double(scale))
        }
        for (glyph, size) in [(Runes.balance, 118.0), ("\u{16DE}", 27.0), ("\u{16C3}", 25.0)] {
            let renderer = ImageRenderer(content: Text(verbatim: glyph).font(TypeRole.rune(size)).foregroundColor(.white))
            renderer.scale = 3
            let text = try XCTUnwrap(renderer.cgImage)
            let ink = centroid(text, scale: 3)
            let m = GlyphGlow.mask(glyph, size: size, blur: RuneGlow.blur)
            let glow = centroid(try XCTUnwrap(m.image.cgImage), scale: m.scale)
            XCTAssertEqual(glow.x, ink.x, accuracy: 0.02 * size, "\(glyph) at \(size): across")
            XCTAssertEqual(glow.y, ink.y, accuracy: 0.02 * size, "\(glyph) at \(size): down")
            XCTAssertEqual(Double(text.height) / 3, 1.15 * size, accuracy: 0.7, "the Text box is ascender to descender")
        }
    }

    /// D-243 (plan test 2): the glow's alphas. core = the rise's envelope (opacity over 0.18) x (rest + (top - rest) x
    /// flare), rest = 0.18 x strength; D-246: the glow (the blurred glyph's alpha, motion.py 5a) is 0.120 on the page and
    /// 0.345 on the hero at the top of a flare, in proportion below it, blurred by 0.18 x the size. Over 16 runes and
    /// 600 s the page's core never passes 0.19 nor its glow 0.120, the hero's never 0.20 nor 0.345. The still frame: the
    /// still pose's 0.10 x strength (page 0.079, hero 0.083), no glow; a still frame never carries a flare (the negative).
    func testTheGlowAlphasAreTheMeasuredOnes() {
        XCTAssertEqual(RuneGlow.pageFlare, 0.19)
        XCTAssertEqual(RuneGlow.heroFlare, 0.20)
        XCTAssertEqual(RuneGlow.blur, 0.18)
        // D-246 fix wave (I1, amended on purpose; 0.145 and 0.8043 before): 1e's cap with the receiver's measured ink.
        XCTAssertEqual(RuneGlow.pageGlow, 0.120)
        XCTAssertEqual(RuneGlow.heroGlow, 0.345)
        XCTAssertEqual(RuneGlow.pageReach, 0.7899)
        XCTAssertEqual(RuneGlow.heroReach, 0.8567)
        let page = Strength.of("norsePageRunes"), hero = Strength.of("norseHeroRunes")
        let want: [(Double, Double, Double, Double, Double, Double, Double)] = [  // strength, top, glowTop, opacity, flare, core, glow
            (0.79, 0.19, 0.120, 0.18, 0, 0.142200, 0.089811), (0.79, 0.19, 0.120, 0.18, 0.5, 0.166100, 0.104905),
            (0.79, 0.19, 0.120, 0.18, 1, 0.19, 0.120), (0.79, 0.19, 0.120, 0.09, 0.5, 0.083050, 0.052453),
            (0.83, 0.20, 0.345, 0.18, 0, 0.149400, 0.257715), (0.83, 0.20, 0.345, 0.18, 0.5, 0.174700, 0.301357),
            (0.83, 0.20, 0.345, 0.18, 1, 0.20, 0.345), (0.83, 0.20, 0.345, 0.09, 0.5, 0.087350, 0.150679),
        ]
        for (k, top, glowTop, opacity, flare, core, glow) in want {
            let a = RuneGlow.alphas(opacity: opacity, strength: k, top: top, flare: flare, glowTop: glowTop, still: false)
            near(a.core, core, 1e-6, "core at \(k) \(opacity) \(flare)")
            near(a.glow, glow, 1e-6, "glow at \(k) \(opacity) \(flare)")
        }
        for (k, still) in [(page, 0.079), (hero, 0.083)] {
            let a = RuneGlow.alphas(opacity: 0.10, strength: k, top: RuneGlow.pageFlare, flare: 1, glowTop: RuneGlow.pageGlow, still: true)
            near(a.core, still, 1e-9, "the still core at \(k)")
            XCTAssertEqual(a.glow, 0, "no glow when still")
        }
        let size = CGSize(width: 402, height: 874)
        var pageCore = 0.0, pageGlow = 0.0
        for i in 0..<RuneDrift.count {
            for step in 0..<(600 * 30 / 7) {
                let t = Double(step * 7) / 30
                let a = RuneGlow.alphas(opacity: RuneDrift.pose(i, t, in: size).opacity, strength: page, top: RuneGlow.pageFlare,
                                        flare: RuneGlow.flare(i, t), glowTop: RuneGlow.pageGlow, still: false)
                pageCore = max(pageCore, a.core)
                pageGlow = max(pageGlow, a.glow)
                let still = RuneGlow.alphas(opacity: RuneDrift.still(i, in: size).opacity, strength: page,
                                            top: RuneGlow.pageFlare, flare: RuneGlow.flare(i, t), glowTop: RuneGlow.pageGlow, still: true)
                XCTAssertEqual(still.core, 0.10 * page, accuracy: 1e-12, "a still rune never flares")
                XCTAssertEqual(still.glow, 0, "a still rune has no glow")
            }
        }
        XCTAssertLessThanOrEqual(pageCore, 0.19 + 1e-12)
        XCTAssertLessThanOrEqual(pageGlow, 0.120 + 1e-12)
        XCTAssertGreaterThan(pageCore, 0.185, "the page runes do flare")
        let heroSize = CGSize(width: 343, height: 260)
        var heroCore = 0.0
        for i in 0..<HeroRunes.count {
            for step in 0..<(600 * 10) {
                let t = Double(step) / 10
                let a = RuneGlow.alphas(opacity: HeroRunes.pose(i, t, in: heroSize).opacity, strength: hero,
                                        top: RuneGlow.heroFlare, flare: HeroRunes.flare(i, t), glowTop: RuneGlow.heroGlow, still: false)
                heroCore = max(heroCore, a.core)
                XCTAssertLessThanOrEqual(a.glow, 0.345 + 1e-12)
            }
        }
        XCTAssertLessThanOrEqual(heroCore, 0.20 + 1e-12)
        XCTAssertGreaterThan(heroCore, 0.19, "the hero runes do flare")
    }

    /// D-243 (plan test 3): the hero's own rising runes, build.py's seven (j = 3...9) in the mock-up's order, each in an
    /// even lane LEFT of the ᚠ (x is the glyph centre, 0.050 + 0.085 x row). They rise as the page's do on the hero's
    /// height; the still frame holds them at the still layout. On the narrowest hero each rune's ink (0.30 x its size)
    /// stays inside the inner frame's interior (heroFrameInset + 1) and its em box inside the hero. D-246 fix wave (I1, M4;
    /// amended on purpose: "no rune's glow reaches another lane's ink" took the receiving ink as D-243's 0.30 disc, which
    /// undercounts the measured ink, 0.4463 x size): with the measured ink no two rows' inks meet at any height, and the
    /// glow a row's ink takes from the others at their lanes, each at its flare's top, is motion.py 1d's lane bound
    /// (0.011468), which the floors hold (AppearanceTests). The mock's 0.050 lane cannot hold row 0's whole em box
    /// inside the interior (3.90 from the edge on a 328 wide hero); the clip trims only empty em box there.
    func testTheHeroRunesTable() {
        XCTAssertEqual(HeroRunes.specs, [
            HeroRunes.Spec(glyph: "\u{16C3}", silver: false, x: 0.050, size: 25, duration: 23, lead: 3, cycle: 11, flareLead: 4, stillY: 0.75),
            HeroRunes.Spec(glyph: "\u{16DC}", silver: true, x: 0.135, size: 15, duration: 23, lead: 7, cycle: 8, flareLead: 9, stillY: 0.67),
            HeroRunes.Spec(glyph: "\u{16BA}", silver: false, x: 0.220, size: 26, duration: 16, lead: 10, cycle: 12, flareLead: 2, stillY: 0.42),
            HeroRunes.Spec(glyph: "\u{16D6}", silver: true, x: 0.305, size: 16, duration: 16, lead: 14, cycle: 9, flareLead: 7, stillY: 0.34),
            HeroRunes.Spec(glyph: "\u{16B1}", silver: false, x: 0.390, size: 20, duration: 16, lead: 18, cycle: 13, flareLead: 1, stillY: 0.26),
            HeroRunes.Spec(glyph: "\u{16CA}", silver: true, x: 0.475, size: 17, duration: 23, lead: 21, cycle: 10, flareLead: 5, stillY: 0.91),
            HeroRunes.Spec(glyph: "\u{16A2}", silver: false, x: 0.560, size: 21, duration: 23, lead: 25, cycle: 7, flareLead: 10, stillY: 0.83),
        ])
        XCTAssertEqual(HeroRunes.count, 7)
        let size = CGSize(width: 343, height: 260)
        let want: [(Int, Double, [Double])] = [  // x, y, size, rotation, opacity, flare
            (0, 0, [17.15, 253.9674, 25, 1.8261, 0.178856, 0]), (0, 7.3, [17.15, 166.2880, 25, 6.2696, 0.144074, 0]),
            (0, 19, [17.15, 25.7609, 25, 13.3913, 0.028986, 0]), (2, 0, [75.46, 117.3438, 26, 8.75, 0.124658, 0]),
            (2, 7.3, [75.46, 267.5547, 26, 1.1375, 0.121875, 0.977296]), (2, 19, [75.46, 65.5469, 26, 11.375, 0.104110, 0.990054]),
            (6, 0, [192.08, 265.9783, 21, 1.2174, 0.130435, 0]), (6, 7.3, [192.08, 178.2989, 21, 5.6609, 0.148839, 0]),
            (6, 19, [192.08, 37.7717, 21, 12.7826, 0.057971, 0]),
        ]
        for (i, t, w) in want {
            let p = HeroRunes.pose(i, t, in: size)
            XCTAssertEqual(p.glyph, HeroRunes.specs[i].glyph)
            XCTAssertEqual(p.silver, HeroRunes.specs[i].silver)
            for (got, exp) in zip([p.x, p.y, p.size, p.rotationDegrees, p.opacity, HeroRunes.flare(i, t)], w) {
                near(got, exp, 1e-4, "hero rune \(i) at \(t)")
            }
        }
        let still: [(Double, Double)] = [(17.15, 195.0), (46.305, 174.2), (75.46, 109.2), (104.615, 88.4), (133.77, 67.6),
                                         (162.925, 236.6), (192.08, 215.8)]
        for (i, xy) in still.enumerated() {
            let p = HeroRunes.still(i, in: size)
            near(p.x, xy.0, 1e-4, "still \(i) x")
            near(p.y, xy.1, 1e-4, "still \(i) y")
            XCTAssertEqual(p.rotationDegrees, 0)
            XCTAssertEqual(p.opacity, 0.10)
        }
        let inner = ThemeArt.m("heroFrameInset") + 1
        for width in [343.0, 402.0] {
            for (i, s) in HeroRunes.specs.enumerated() {
                let x = width * s.x
                XCTAssertGreaterThanOrEqual(x - 0.30 * s.size, inner, "row \(i)'s ink inside the interior at \(width)")
                XCTAssertLessThanOrEqual(x + 0.30 * s.size, width - inner, "row \(i)'s ink inside the interior at \(width)")
                XCTAssertGreaterThanOrEqual(x - s.size / 2, 0, "row \(i)'s em box inside the hero at \(width)")
            }
        }
        // The lanes with the measured ink (motion.py 1d, on 328, 343 and 402): row j's glow on row i's ink is
        // heroGlow x min(peak, Phi(-(r - ink s_j) / (blur s_j))), r the lane distance less row i's ink.
        func phi(_ x: Double) -> Double { erfc(-x / 2.0.squareRoot()) / 2 }
        let ink = GlyphGlowGoldens.inkHero
        var laneGlow = 0.0
        for width in [328.0, 343.0, 402.0] {
            for (i, s) in HeroRunes.specs.enumerated() {
                var glow = 0.0
                for (j, o) in HeroRunes.specs.enumerated() where j != i {
                    let gap = abs(s.x - o.x) * width
                    XCTAssertGreaterThanOrEqual(gap, ink * (s.size + o.size), "rows \(i) and \(j)'s measured inks never meet at \(width)")
                    let r = max(0, gap - ink * s.size)
                    glow += RuneGlow.heroGlow * min(GlyphGlowGoldens.hero, phi(-(r - ink * o.size) / (RuneGlow.blur * o.size)))
                }
                laneGlow = max(laneGlow, glow)
            }
        }
        near(laneGlow, GlyphGlowGoldens.heroLaneGlow, 1e-5, "motion.py 1d's lane bound (its inks rounded to 4 places)")
        // Why the rule changed (the negative of the old one): with the measured ink, "the glow stays off another lane's
        // ink" (gap >= heroReach s_j + ink s_i) is false on the narrowest hero; D-243's 0.30 receiver hid it.
        let crossing = HeroRunes.specs.indices.flatMap { i in HeroRunes.specs.indices.filter { $0 != i }.map { (i, $0) } }
            .filter { i, j in
                abs(HeroRunes.specs[i].x - HeroRunes.specs[j].x) * 328 < RuneGlow.heroReach * HeroRunes.specs[j].size + ink * HeroRunes.specs[i].size
            }
        XCTAssertFalse(crossing.isEmpty, "a glow reaches a neighbour's measured ink")
    }

    /// D-243 (plan test 4): the hero runes keep left of the ᚠ and its glow. The ᚠ is measured in the bundled Vardr Runes
    /// Carved at 118 (its advance, 460/1000 em, motion.py 1d); it sits trailing 13 from the hero's edge, so its centre is
    /// W - 13 - w / 2 and (D-246) its glow is seen to `WatermarkGlow.glowLeft` x 118 (71.41) left of that (D-243's halo:
    /// 59). On the narrowest hero (343) every rune's reach (centre + the farther of 0.71 x size for a turned em box and
    /// its glow's RuneGlow.heroReach x size) stays left of that.
    func testTheHeroRunesStayLeftOfTheFehu() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "VardrRunes-CarvedRegular", withExtension: "ttf"), "the rune font is not bundled")
        let descriptors = try XCTUnwrap(CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor])
        let size = ThemeArt.m("heroWatermark")
        XCTAssertEqual(size, 118)
        let font = CTFontCreateWithFontDescriptor(try XCTUnwrap(descriptors.first), size, nil)
        var chars = Array(Runes.balance.utf16)
        XCTAssertEqual(Runes.balance, "\u{16A0}")
        var glyph = [CGGlyph](repeating: 0, count: chars.count)
        XCTAssertTrue(CTFontGetGlyphsForCharacters(font, &chars, &glyph, chars.count))
        var advance = CGSize.zero
        CTFontGetAdvancesForGlyphs(font, .horizontal, &glyph, &advance, 1)
        XCTAssertEqual(Double(advance.width), 54.28, accuracy: 0.01, "motion.py: 0.460 em at 118")
        let width = 343.0
        XCTAssertEqual(WatermarkGlow.glowLeft, 0.6052)
        let fehuGlowLeft = width - ThemeArt.m("heroWatermarkTrailing") - Double(advance.width) / 2 - WatermarkGlow.glowLeft * size
        let reach = HeroRunes.specs.map { width * $0.x + max(0.71, RuneGlow.heroReach) * $0.size }.max() ?? .infinity
        XCTAssertLessThan(reach, fehuGlowLeft, "the hero runes keep left of the ᚠ's glow")
        near(reach, 210.07, 0.01, "motion.py 1d, 343 wide")
        near(fehuGlowLeft, 231.45, 0.01, "motion.py 1d, 343 wide")
    }

    /// D-243 (plan test 5): the ᚠ glows now and then: 0.16 at rest, up to 0.208 on a 9 s cycle (rising from 0.55 of it,
    /// top at 0.70, rest again at 0.88, each half eased in and out), D-246: over its own blurred shape at 0.305 at the top
    /// of the glow (motion.py 5a: the disc's light over its ink; 1c''s cap 0.625). Still: 0.16, no glow, at any time
    /// (today's ᚠ, pixel for pixel).
    func testTheWatermarkGlows() {
        XCTAssertEqual(WatermarkGlow.rest, 0.16)
        XCTAssertEqual(WatermarkGlow.peak, 0.208)
        XCTAssertEqual(WatermarkGlow.cycle, 9)
        XCTAssertEqual([WatermarkGlow.rise, WatermarkGlow.top, WatermarkGlow.fall], [0.55, 0.70, 0.88])
        XCTAssertEqual(WatermarkGlow.glyphGlow, 0.305)
        let want: [(Double, Double, Double, Double)] = [  // t, glow, alpha, glyph glow
            (0, 0, 0.16, 0), (4.95, 0, 0.16, 0), (5.6, 0.468099, 0.182469, 0.142770), (6.3, 1, 0.208, 0.305),
            (7.0, 0.615709, 0.189554, 0.187791), (7.92, 0, 0.16, 0), (8.99, 0, 0.16, 0),
        ]
        for (t, glow, alpha, glyphGlow) in want {
            near(WatermarkGlow.glow(t), glow, 1e-6, "glow at \(t)")
            near(WatermarkGlow.alpha(t, still: false), alpha, 1e-6, "alpha at \(t)")
            near(WatermarkGlow.glyphGlowAlpha(t, still: false), glyphGlow, 1e-6, "glyph glow at \(t)")
            XCTAssertEqual(WatermarkGlow.alpha(t, still: true), 0.16, "still at \(t)")
            XCTAssertEqual(WatermarkGlow.glyphGlowAlpha(t, still: true), 0, "no glow when still at \(t)")
        }
        for step in 0..<900 {
            let t = Double(step) * 0.02
            near(WatermarkGlow.glow(t), WatermarkGlow.glow(t + 9), 1e-9, "a 9 s cycle at \(t)")
            XCTAssertTrue(WatermarkGlow.alpha(t, still: false) >= 0.16 - 1e-12 && WatermarkGlow.alpha(t, still: false) <= 0.208 + 1e-12)
        }
    }

    /// D-243 (plan test 6, the engine half): You's ring turns clockwise, one turn per 90 s from upright, in [0, 360);
    /// upright when still, and a clock that is not finite draws it upright.
    func testTheRingTurns() {
        XCTAssertEqual(RingTurn.period, 90)
        XCTAssertEqual(RingTurn.still, 0)
        XCTAssertEqual(RingTurn.litGlow, 0.995)
        let want: [(Double, Double)] = [(0, 0), (22.5, 90), (45, 180), (89.99, 359.96), (90, 0), (900, 0), (-22.5, 270),
                                        (.nan, 0), (.infinity, 0), (-.infinity, 0)]
        for (s, d) in want { near(RingTurn.degrees(s), d, 1e-6, "ring at \(s)") }
        XCTAssertEqual(RingTurn.degrees(RingTurn.still), 0)
        for step in 0..<2000 {
            let d = RingTurn.degrees(Double(step) * 0.37 - 100)
            XCTAssertTrue(d >= 0 && d < 360, "in [0, 360) at step \(step)")
        }
    }

    /// D-243 (plan test 6, the renderers' half): You's ring turns inside `ArtTurn` on the art's clock (`MotionClock.artTicks`:
    /// Reduce Motion, Less motion and power saving still it; no read of power saving of its own), from upright each time it
    /// starts, and the picture inside it does not turn. Negative: the done check's ring keeps its own moment's turn and is
    /// never wrapped in ArtTurn. Every lit rune carries the static glow (D-246: its glyph's, D-243's halo), in both rings.
    func testYouRingTurnsAndTheDoneRingKeepsItsOwnClock() throws {
        let profile = try appSource("Profile.swift")
        let ringed = try XCTUnwrap(profile.components(separatedBy: "private var ringed: some View {").last?.components(separatedBy: "\n    }\n").first)
        XCTAssertTrue(ringed.contains("""
                        ArtTurn {
                            RuneRing(done: shown.done, total: shown.total)
                        }
        """), "the ring turns inside ArtTurn")
        XCTAssertEqual(ringed.components(separatedBy: "ArtTurn {").count - 1, 1)
        let avatar = try XCTUnwrap(ringed.range(of: "AvatarView(avatar: avatar, name: name, size: 104)"))
        let turnEnd = try XCTUnwrap(ringed.range(of: "RuneRing(done: shown.done, total: shown.total)\n                }"))
        XCTAssertGreaterThan(avatar.lowerBound, turnEnd.upperBound, "the picture sits outside the turn")
        let turn = try motionSection("struct ArtTurn<Content: View>: View {", to: "\n}\n")
        XCTAssertTrue(turn.contains("let tick = MotionClock.artTicks(reduceMotion: reduceMotion, active: phase == .active && visible && onScreen)"),
                      "the art's clock")
        XCTAssertFalse(turn.contains("momentTicks") || turn.contains("PowerState"), "no other rule, no read of its own")
        XCTAssertTrue(turn.contains("TimelineView(ArtSchedule(paused: !tick)) { timeline in"), "on the art's shared grid")
        XCTAssertTrue(turn.contains("MomentClock.elapsed(tick: tick, now: now, startedAt: since ?? now, still: RingTurn.still)"),
                      "upright when still")
        XCTAssertTrue(turn.contains(".onChange(of: tick, initial: true) { _, ticking in since = ticking ? Date().timeIntervalSinceReferenceDate : nil }"),
                      "from upright each time it starts")
        XCTAssertTrue(turn.contains(".accessibilityHidden(true)") && turn.contains(".allowsHitTesting(false)"), "decoration")
        // The negative: the done check's ring turns on its moment (CheckMotion), never in ArtTurn.
        let done = try appSource("DoneMoments.swift")
        XCTAssertFalse(done.contains("ArtTurn"), "the done ring keeps its own moment's turn")
        XCTAssertTrue(done.contains("RuneRing(done: 1, total: 1)\n                        .rotationEffect(.degrees(pose.degrees))"))
        // The lit glow: in RuneRing itself, so both rings carry it. D-246: the rune's own blurred glyph, at its size.
        let ring = try XCTUnwrap(try appSource("YouProgress.swift").components(separatedBy: "extension RuneRing: View {").last?
            .components(separatedBy: "\n}\n").first)
        XCTAssertTrue(ring.contains("""
                                if i < lit {
                                    GlyphGlow.view(rune, size: size, blur: RuneGlow.blur)
                                        .foregroundStyle(Tokens.ring)
                                        .opacity(RingTurn.litGlow)
                                }
        """), "lit runes only, each its own glyph's glow in the ring's gold")
    }

    // MARK: - D-250 (decision 194): the change motion (goldens from .drafts/d250/goldens.out, re-derived in Python)

    /// ChangeMotion's values POINT at the ones that already exist (the audit's table): the switch's travel, the toast's
    /// way in and its ease and rise, Android's page, the welcome's sweep, the bar's fade. Goldens retyped, and the
    /// pointers read in the source (a literal copy would drift). Negative: none of them is a literal of its own.
    func testChangeMotionPointsAtTheValuesThatExist() throws {
        XCTAssertEqual(ChangeMotion.change, 0.2, accuracy: 1e-12)
        XCTAssertEqual(ChangeMotion.number, 0.2, accuracy: 1e-12)
        XCTAssertTrue(ChangeMotion.numberEase == (0.2, 0.7, 0.3, 1.0))
        XCTAssertEqual(ChangeMotion.rise, 10)
        XCTAssertEqual(ChangeMotion.page, 0.25)
        XCTAssertEqual(ChangeMotion.fill, 0.9)
        XCTAssertEqual(ChangeMotion.fade, 0.15)
        XCTAssertEqual(ChangeMotion.change, Double(ThemeArt.m("switchTravel")))
        XCTAssertEqual(ChangeMotion.number, ToastMotion.fadeIn)
        XCTAssertEqual(ChangeMotion.fill, WelcomeProgress.sweepSeconds)
        XCTAssertEqual(ChangeMotion.Kind.allCases.map(ChangeMotion.seconds), [0.2, 0.2, 0.25, 0.9, 0.15].map { $0 })
        let table = try motionSection("enum ChangeMotion {", to: "\n}\n")
        for pointer in ["static let change = Double(ThemeArt.m(\"switchTravel\"))", "static let number = ToastMotion.fadeIn",
                        "static let numberEase = ToastMotion.ease", "static let rise = ToastMotion.rise",
                        "static let fill = WelcomeProgress.sweepSeconds"] {
            XCTAssertTrue(table.contains(pointer), pointer)
        }
        XCTAssertFalse(table.contains("static let change = 0") || table.contains("static let number = 0")
                       || table.contains("static let fill = 0"), "a value of its own")
        // Every kind is a cut when reduced and moves otherwise; nothing reads power saving.
        for kind in ChangeMotion.Kind.allCases {
            XCTAssertNil(ChangeMotion.animation(kind, reduced: true), "\(kind) cuts when reduced")
            XCTAssertNotNil(ChangeMotion.animation(kind, reduced: false), "\(kind) moves")
        }
        XCTAssertFalse(table.contains("PowerState") || table.contains("lowPower"), "never power saving")
    }

    /// The fill sweeps on the welcome's own in-out ease (goldens.out). Negatives: not linear at a quarter, never past 1.
    func testTheFillSweepsAsTheWelcomeDoes() {
        let goldens: [(Double, Double)] = [(0, 0), (0.1, 0.024691), (0.225, 0.125), (0.45, 0.5), (0.675, 0.875),
                                           (0.8, 0.975309), (0.9, 1), (1.2, 1)]
        for (t, share) in goldens { near(FillCurve.share(t, duration: 0.9), share, 1e-6, "share at \(t)") }
        XCTAssertNotEqual(FillCurve.share(0.225, duration: 0.9), 0.25, accuracy: 1e-3, "not linear")
        for step in 0...40 {
            let t = Double(step) * 0.05
            XCTAssertTrue((0...1).contains(FillCurve.share(t, duration: 0.9)), "in 0...1 at \(t)")
        }
    }

    /// A roll counts down only when the value falls below the one shown; the first value counts up. A fill sweeps only
    /// from a value already shown, and never when reduced (the door). Negatives in each table.
    func testTheRollAndTheFillKnowTheirWay() {
        XCTAssertFalse(MoneyChange.countsDown(from: nil, to: 5), "the first value")
        XCTAssertTrue(MoneyChange.countsDown(from: 10, to: 5))
        XCTAssertFalse(MoneyChange.countsDown(from: 5, to: 10))
        XCTAssertFalse(MoneyChange.countsDown(from: 5, to: 5))
        XCTAssertTrue(MoneyChange.countsDown(from: 0, to: -300), "a balance going negative counts down")
        XCTAssertFalse(FillMotion.sweeps(reduced: false, seen: nil), "the first read lands at once")
        XCTAssertTrue(FillMotion.sweeps(reduced: false, seen: 0.3))
        XCTAssertFalse(FillMotion.sweeps(reduced: true, seen: 0.3), "reduced: a cut")
    }

    /// You's ring sweeps in its share and lights runes as it passes them: at rest the count's own integer rounding, mid
    /// sweep the share's, which agree at every rest (totals 1 to 8, goldens.py). A sweep from 5/7 to 6/7 lights 18 at
    /// 0.729167. Negative: just under that share it is still 17.
    func testTheRingLightsRunesAsItSweeps() {
        for total in 1...8 {
            for done in 0...total {
                let share = RuneRing.share(done: done, total: total)
                XCTAssertEqual(RuneRing.lit(share: share), RuneRing.lit(done: done, total: total), "\(done) of \(total)")
                XCTAssertEqual(RuneRing(done: done, total: total).fill, share)
                XCTAssertEqual(CompletionRing(done: done, total: total).fill, share)
            }
        }
        XCTAssertEqual(RuneRing.share(done: 3, total: 0), 0)
        XCTAssertEqual(RuneRing.share(done: 9, total: 7), 1, "never past the whole")
        XCTAssertEqual(RuneRing.lit(share: 0.729167), 18)
        XCTAssertEqual(RuneRing.lit(share: 0.7291), 17)
        XCTAssertEqual(RuneRing.lit(share: 1), 24)
        var last = 0
        for step in 0...1000 {
            let lit = RuneRing.lit(share: Double(step) / 1000)
            XCTAssertGreaterThanOrEqual(lit, last, "never unlights while it fills")
            last = lit
        }
    }

    /// Rows with no id of their own get unique keys: each repeat numbered. Negative: no two keys alike.
    func testRowsWithoutIdsGetUniqueKeys() {
        XCTAssertEqual(MotionKeys.unique(["a", "b", "a", "a"]), ["a", "b", "a#1", "a#2"])
        XCTAssertEqual(MotionKeys.unique([]), [])
        let keys = MotionKeys.unique(["Sam/INR", "Sam/INR", "Ana/INR", "Sam/USD"])
        XCTAssertEqual(Set(keys).count, keys.count)
    }

    /// Settle up's card is keyed by its group, currency and person, never its place or its amount: a partial settle
    /// keeps the card (the amount rolls), another currency is another card.
    func testASettleCardKeepsItsIdentity() {
        XCTAssertEqual(SettleAll.cardID(group: "g", currency: "INR", entry: ShareOut(id: "p", minorUnits: -500)), "g/INR/p")
        XCTAssertEqual(SettleAll.cardID(group: "g", currency: "INR", entry: ShareOut(id: "p", minorUnits: -500)),
                       SettleAll.cardID(group: "g", currency: "INR", entry: ShareOut(id: "p", minorUnits: -200)), "a partial keeps the card")
        XCTAssertNotEqual(SettleAll.cardID(group: "g", currency: "INR", entry: ShareOut(id: "p", minorUnits: -500)),
                          SettleAll.cardID(group: "g", currency: "USD", entry: ShareOut(id: "p", minorUnits: -500)))
    }

    /// Whether a helper's source reads the door: the environment reader, the animation through `ChangeMotion.animation`
    /// with `reduceMotion` as its input, and no read of power saving or the phone's own flag.
    static func readsTheDoor(_ helper: String) -> Bool {
        helper.contains("@Environment(\\.motionReduced) private var reduceMotion")
            && helper.components(separatedBy: "\n").contains { $0.contains("ChangeMotion.animation(") && $0.contains("reduceMotion") }
            && !["PowerState", "lowPower", "artTicks", "isReduceMotionEnabled", "accessibilityReduceMotion", "withAnimation"]
                .contains { helper.contains($0) }
    }

    /// D-250: every change helper reads the door and never power saving; the window's crossfade asks the imperative door
    /// first. Negatives: the checker refuses a helper with no door, one that reads power saving, and one that animates
    /// with `withAnimation` behind the door's back.
    func testEveryChangeHelperReadsTheDoor() throws {
        for start in ["struct RowsMotion<Value: Equatable>: ViewModifier {", "struct Reveal<Value: Equatable>: ViewModifier {",
                      "struct MoneyChange: ViewModifier {", "struct FillMotion: ViewModifier {"] {
            XCTAssertTrue(Self.readsTheDoor(try motionSection(start, to: "\n}\n")), start)
        }
        let money = try motionSection("struct MoneyChange: ViewModifier {", to: "\n}\n")
        XCTAssertTrue(money.contains(".contentTransition(reduceMotion ? .identity : .numericText(countsDown:"), "the roll cuts too")
        XCTAssertFalse(money.contains("Double("), "no Double made from money")
        // The leaving card slides out to the trailing edge and fades; it comes back by fading (both inert outside a
        // door-read change).
        let leaves = try motionSection("static var cardLeaves: AnyTransition {", to: "\n    }\n")
        XCTAssertTrue(leaves.contains(".asymmetric(insertion: .opacity, removal: .move(edge: .trailing).combined(with: .opacity))"))
        let fade = try motionSection("enum ThemeCrossfade {", to: "\n}\n")
        XCTAssertTrue(fade.contains("guard !MotionSetting.reducedNow(),"), "the imperative door first")
        XCTAssertTrue(fade.contains("UIView.animate(withDuration: ChangeMotion.fade,"))
        XCTAssertTrue(fade.contains("snapshot.isUserInteractionEnabled = false"), "the snapshot takes no touches")
        XCTAssertFalse(fade.contains("PowerState") || fade.contains("lowPower"))
        // The checker's negatives.
        XCTAssertFalse(Self.readsTheDoor("struct X: ViewModifier { func body(content: Content) -> some View { content.animation(.easeInOut, value: v) } }"))
        XCTAssertFalse(Self.readsTheDoor("""
            @Environment(\\.motionReduced) private var reduceMotion
            content.animation(ChangeMotion.animation(.change, reduced: reduceMotion || PowerState.shared.lowPower), value: v)
            """))
        XCTAssertFalse(Self.readsTheDoor("""
            @Environment(\\.motionReduced) private var reduceMotion
            content.animation(ChangeMotion.animation(.change, reduced: reduceMotion), value: v).onTapGesture { withAnimation { v.toggle() } }
            """))
    }

    /// D-250: the sweep's sites move through the helpers, and nothing outside them animates a change behind the door:
    /// every number roll is MoneyChange's (no other `.contentTransition(`), every `withAnimation` left in the app is one
    /// of the four gated ones (the App icon rows, two pops to root, the scroll to top), and no row list keyed by its
    /// place remains where rows come and go. Negative: New group's ungated spring is gone.
    func testTheSweepMovesThroughTheHelpers() throws {
        var transitions: [String: Int] = [:], springs: [String: Int] = [:]
        for file in try appFiles() {
            let text = try String(contentsOf: file, encoding: .utf8)
            let code = text.components(separatedBy: "\n").map { line in
                line.range(of: "//").map { String(line[..<$0.lowerBound]) } ?? line
            }.joined(separator: "\n")
            let t = code.components(separatedBy: ".contentTransition(").count - 1
            let w = code.components(separatedBy: "withAnimation").count - 1
            if t > 0 { transitions[file.lastPathComponent] = t }
            if w > 0 { springs[file.lastPathComponent] = w }
        }
        XCTAssertEqual(transitions, ["ThemeMotion.swift": 1], "every roll is MoneyChange's")
        XCTAssertEqual(springs, ["AccountHubScreen.swift": 1, "HomeShell.swift": 2, "SkiptiTabBar.swift": 1],
                       "only the gated animations")
        let sites: [(String, String)] = [
            ("OverviewScreen.swift", ".moneyChange(first.amountMinor)"), ("OverviewScreen.swift", ".moneyChange(p.netMinor)"),
            ("OverviewScreen.swift", ".rowsMotion(homeShape)"), ("OverviewScreen.swift", ".rowsMotion(pageShape)"),
            ("OverviewScreen.swift", "id: \\.element.id) { i, e in"), ("HomeParts.swift", ".moneyChange(nets.first?.amountMinor ?? 0)"),
            ("HomeParts.swift", ".fillMotion(SettledMeter.fraction(basisPoints))"), ("Settled.swift", ".fillMotion(SettledMeter.fraction(b.basisPoints))"),
            ("SettleAll.swift", ".rowsMotion(SettleAll.shape(sections))"), ("SettleAll.swift", "ForEach(debts, id: \\.id) { debt in"),
            ("SettleAll.swift", ".moneyChange(entry.minorUnits)"), ("ExpenseListView.swift", ".rowsMotion(shape)"),
            ("ExpenseListView.swift", ".rowsMotion(expenses.isEmpty)"), ("AddExpenseScreen.swift", ".rowsMotion(formShape)"),
            ("AddExpenseScreen.swift", ".reveals(error)"), ("GroupSheets.swift", ".rowsMotion(people.rows.map(\\.id))"),
            ("Spending.swift", ".rowsMotion(view)"), ("Spending.swift", ".rowsMotion(pageShape)"), ("Spending.swift", ".moneyChange(first.totalMinor)"),
            ("AccountHubScreen.swift", ".rowsMotion(shown.chosen)"), ("AccountHubScreen.swift", "ThemeCrossfade.change {"),
            ("Profile.swift", ".fillMotion(progress.map { RuneRing.share(done: $0.done, total: $0.total) })"),
            ("SettleAll.swift", ".transition(.cardLeaves)"), ("ExpenseListView.swift", ".transition(.cardLeaves)"),
            ("Spending.swift", ".rowsMotion(\"\\(view.label)|\\(first?.currencyCode ?? \"none\")|\\(more)\")"),
            ("YouProgress.swift", ".moneyChange(Int64(done))"), ("Welcome.swift", "ChangeMotion.animation(.page, reduced: reduceMotion)"),
        ]
        for (file, site) in sites { XCTAssertTrue(try appSource(file).contains(site), "\(file): \(site)") }
        XCTAssertEqual(try appSource("DesignTokens.swift").components(separatedBy: ".rowsMotion(isEnabled)").count - 1, 4,
                       "every button style brightens through the door")
        let settle = try appSource("SettleAll.swift")
        XCTAssertFalse(settle.contains("Array(debts.enumerated()), id: \\.offset"), "Settle up's cards keyed by place")
        XCTAssertFalse(try appSource("GroupSheets.swift").contains("withAnimation"))
    }

}

/// D-231: what `testTheRootHandsLessMotionToEveryView` saw.
private final class MotionSeen { var value: Bool? }

/// D-231: reads `\.motionReduced` where a view would.
private struct MotionProbe: View {
    let box: MotionSeen
    @Environment(\.motionReduced) private var reduced

    var body: some View {
        box.value = reduced
        return Color.clear.frame(width: 1, height: 1)
    }
}
