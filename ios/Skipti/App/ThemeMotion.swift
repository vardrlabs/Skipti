import CoreGraphics
import Foundation
import Observation
import SwiftUI

/// D-214 (E2 motion, ADR-0041 "Theme art", the founder, 2026-10-15): every animation is a pure function of time and
/// a fixed seed; the renderers (ArtCanvas, HeroArt) only draw what these return. Values from .drafts/d214/motion.py,
/// which ports the Look Book's formulas. Android's twin is ThemeMotion.kt; ThemeMotionTest there reads this file.
enum MotionClock {
    /// The art never draws faster than this.
    static let fps: Double = 30

    /// The spec's still mode: no ticking under Reduce Motion or Low Power, or when the art is not on an active screen.
    static func shouldTick(reduceMotion: Bool, lowPower: Bool, active: Bool) -> Bool {
        !reduceMotion && !lowPower && active
    }

    /// D-239 (decision 137, the founder's): a moment (a done check, the settle celebration and its burst, a switch's
    /// travel, a row opening) is not art: power saving never stills it, only Reduce Motion or Less motion does. The art
    /// alone keeps `shouldTick`'s Low Power rule: iOS 26 and later's Adaptive Power reported Low Power on the founder's
    /// iPhone 17 with the switch off, and every moment in the app froze.
    static func momentTicks(reduceMotion: Bool, active: Bool) -> Bool {
        shouldTick(reduceMotion: reduceMotion, lowPower: false, active: active)
    }

    /// D-239 review (I1): the art rule, for a view that draws a moment and then art (the done check's Norse ring keeps
    /// turning after the check lands; that endless turn is decoration, so power saving pauses it). The art's one read
    /// of power saving outside the canvas and the runes.
    @MainActor static func artTicks(reduceMotion: Bool, active: Bool) -> Bool {
        shouldTick(reduceMotion: reduceMotion, lowPower: PowerState.shared.lowPower, active: active)
    }
}

/// D-231 (decisions 68 to 70): Less motion, the app's own Reduce Motion. Stored with the other appearance preferences
/// (ADR-0036: UserDefaults, never the vault). Android's twin is `MotionSetting` in ThemeMotion.kt; ThemeMotionTest there
/// reads this enum's key and bound.
enum MotionSetting {
    static let storageKey = "appearance.lessMotion"
    /// 3 GiB: a phone with less memory starts with Less motion on.
    static let lowRamBytes: UInt64 = 3 * 1024 * 1024 * 1024

    /// The stored value once the person has set it; until then, whether the phone is low on memory.
    static func parse(stored: Bool?, lowRam: Bool) -> Bool { stored ?? lowRam }

    /// A low-memory phone: the platform says so, or it has under 3 GiB. iOS has no such flag, so it passes false.
    static func lowRam(totalBytes: UInt64, lowRamFlag: Bool) -> Bool { lowRamFlag || totalBytes < lowRamBytes }

    /// The one combine: the phone's own setting or the switch. The switch only adds stillness.
    static func reduced(system: Bool, lessMotion: Bool) -> Bool { system || lessMotion }

    /// This phone's low-memory default, read once per launch and never stored.
    static let lowRamAtLaunch = MotionSetting.lowRam(totalBytes: ProcessInfo.processInfo.physicalMemory, lowRamFlag: false)
}

/// D-231 (decision 69): the Less motion row's words, verbatim on both phones (Android's ThemeMotionTest compares).
enum MotionCopy {
    static let title = "Less motion"
    static let summary = "Still art, fewer animations"
}

/// The strengths the founder chose (2026-10-15): each multiplies every Look Book opacity of its layer (motion.py 1b).
enum Strength {
    static let metrics: [String: Double] = [
        "auroraDarkPageCurtains": 0.26, "auroraDarkPageStars": 0.16, "auroraLightPageCurtains": 1.0,
        "auroraHeroCurtains": 1.0, "auroraHeroStars": 1.0, "norsePageRunes": 0.79, "norseHero": 1.0,
        "vardrLightHeroSigns": 0.60, "vardrDarkHeroSigns": 0.54,
        // D-226 (.drafts/d226/art.py section 48): Norse's page glows at the Look Book's own strength.
        "norsePageGlow": 1.0,
        // D-243 (.drafts/d214/motion.py section 6): the hero runes' rest, the page's rest share of its flare (0.142 / 0.19)
        // times the hero's flare (0.20), over the rune peak 0.18.
        "norseHeroRunes": 0.83,
    ]

    static func of(_ key: String) -> Double { metrics[key]! }
}

/// Aurora's three curtains, swaying on 13, 17 and 11 s cycles (the Look Book's `sway`).
enum Curtains {
    struct Stop { let r: Double; let g: Double; let b: Double; let alpha: Double; let at: Double }
    struct Spec {
        let left: Double
        let height: Double
        let period: Double
        let delay: Double
        let lightOpacity: Double
        let nightOpacity: Double
        let stops: [Stop]
    }
    struct Pose: Equatable { let x: Double; let y: Double; let width: Double; let height: Double; let skewDegrees: Double; let sx: Double; let sy: Double }

    static let width = 0.70
    static let top = -0.12
    private static let pinkGreenCyan: [Stop] = [
        Stop(r: 214, g: 92, b: 170, alpha: 0, at: 0), Stop(r: 214, g: 92, b: 170, alpha: 0.42, at: 0.12),
        Stop(r: 69, g: 230, b: 176, alpha: 0.85, at: 0.34), Stop(r: 31, g: 182, b: 201, alpha: 0.35, at: 0.64),
        Stop(r: 31, g: 182, b: 201, alpha: 0, at: 1),
    ]
    static let specs: [Spec] = [
        Spec(left: -0.10, height: 0.70, period: 13, delay: 0, lightOpacity: 0.35, nightOpacity: 0.80, stops: pinkGreenCyan),
        Spec(left: 0.30, height: 0.58, period: 17, delay: -6, lightOpacity: 0.35, nightOpacity: 0.62, stops: pinkGreenCyan),
        Spec(left: 0.55, height: 0.48, period: 11, delay: -3, lightOpacity: 0.35, nightOpacity: 0.50, stops: [
            Stop(r: 69, g: 230, b: 176, alpha: 0, at: 0), Stop(r: 69, g: 230, b: 176, alpha: 0.70, at: 0.30),
            Stop(r: 31, g: 182, b: 201, alpha: 0.30, at: 0.70), Stop(r: 31, g: 182, b: 201, alpha: 0, at: 1),
        ]),
    ]
    /// (dx, dy, skew, sx, sy) at 0, 0.5 and 1 of a swing.
    static let keys: [[Double]] = [[-0.12, 0, -16, 1, 1], [0.06, 0.02, 6, 1.05, 1.15], [0.14, -0.02, -8, 0.95, 1]]
    /// The still frame's time.
    static let still: Double = 0

    /// The streaks, the Look Book's 11 pt pattern: the whole curtain at `streakBase`, then the bars (offset, width in pt)
    /// at `streakBar` more. A blur wider than the pattern leaves its mean, 0.60 (the Look Book's 2 x 1 + 4 x 0.35 +
    /// 1 x 1 + 4 x 0.55 over 11); the floor tests measure the curtains at that mean.
    static let streakPitch: Double = 11
    static let streakBars: [(x: Double, width: Double)] = [(0, 2), (6, 1)]
    static let streakBase = 0.45
    static let streakBar = 0.55
    static var streakMean: Double { streakBase + streakBar * streakBars.reduce(0) { $0 + $1.width } / streakPitch }

    /// The arched hem, the Look Book's `border-radius: 0 0 50% 50% / 0 0 30% 30%`: below `hemTop` of its height a
    /// curtain narrows in two quarter ellipses of radii (half its width, 0.30 of its height).
    static let hemTop = 0.70
    /// Four thirds of (root 2 - 1): a cubic's control length for a quarter ellipse.
    static let kappa = 0.5522847498

    /// Whether a point of a curtain's own unit square (fx across, fy down) lies inside its arched outline.
    static func insideHem(_ fx: Double, _ fy: Double) -> Bool {
        guard fx >= 0, fx <= 1, fy >= 0, fy <= 1 else { return false }
        if fy <= hemTop { return true }
        let dx = (fx - 0.5) / 0.5, dy = (fy - hemTop) / (1 - hemTop)
        return dx * dx + dy * dy <= 1
    }

    /// The hem's two cubics in a w x h curtain, (c1, c2, end) each: from (w, hemTop h) round to the bottom centre,
    /// then up to (0, hemTop h). The outline is (0, 0), (w, 0), (w, hemTop h), these two, closed.
    static func hemCurves(_ w: Double, _ h: Double) -> [[Double]] {
        let top = hemTop * h, ry = (1 - hemTop) * h, rx = w / 2
        return [[w, top + kappa * ry, rx + kappa * rx, h, rx, h], [rx - kappa * rx, h, 0, top + kappa * ry, 0, top]]
    }

    static func pose(_ i: Int, _ t: Double) -> Pose {
        let s = specs[i]
        let u = (t - s.delay) / s.period
        let n = u.rounded(.down)
        let f = u - n
        let p = Int(n).isMultiple(of: 2) ? f : 1 - f
        let (a, b, seg) = p < 0.5 ? (keys[0], keys[1], p / 0.5) : (keys[1], keys[2], (p - 0.5) / 0.5)
        let e = (1 - cos(Double.pi * seg)) / 2
        let v = zip(a, b).map { $0 + ($1 - $0) * e }
        return Pose(x: s.left + v[0] * width, y: top + v[1] * s.height, width: width, height: s.height,
                    skewDegrees: v[2], sx: v[3], sy: v[4])
    }
}

/// The night sky's stars (still), the Look Book's eight.
enum Stars {
    static let all: [(x: Double, y: Double, radius: Double, alpha: Double)] = [
        (0.12, 0.18, 1, 0.9), (0.33, 0.09, 1, 0.7), (0.61, 0.14, 1.2, 0.9), (0.82, 0.26, 1, 0.6),
        (0.47, 0.31, 1, 0.5), (0.90, 0.08, 1, 0.8), (0.24, 0.44, 1, 0.4), (0.70, 0.40, 1, 0.5),
    ]
}

/// Norse's runes rising behind every page (the Look Book's `runefall`).
enum RuneDrift {
    struct Pose: Equatable { let glyph: String; let silver: Bool; let x: Double; let y: Double; let size: Double; let rotationDegrees: Double; let opacity: Double }

    static let count = 16
    static let peak = 0.18

    /// D-243 stage 3 (the founder, 2026-10-20: "Give each rune its own lane"): rune i rises in lane `lanes[i]` of sixteen,
    /// evenly spread from 2% to 94% of the width (lane k's centre at 2 + 92 k / 15 percent; x is the glyph's centre), so no
    /// two runes' ink ever meets and the floors hold on every frame (.drafts/d214/motion.py 1e). D-214's lanes,
    /// (i * 37) % 92 + 2 percent, crowded the sixteen into five clusters. The permutation keeps neighbours apart in colour
    /// and size (.drafts/d243/lanes/choose.py); glyphs, sizes, colours, speeds and flares are unchanged. Android's twin is
    /// `RuneDrift.LANES`; its ThemeMotionTest reads this table.
    static let lanes = [1, 8, 14, 5, 11, 2, 7, 15, 6, 10, 0, 9, 13, 4, 12, 3]

    private static func base(_ i: Int, _ size: CGSize) -> (String, Bool, Double, Double) {
        (Runes.futhark[(i * 7) % 24], i % 3 == 0, Double(size.width) * (2 + 92 * Double(lanes[i]) / 15) / 100, Double(14 + (i * 5) % 14))
    }

    static func opacity(_ p: Double) -> Double {
        if p < 0.12 { return peak * p / 0.12 }
        if p < 0.85 { return peak + (0.10 - peak) * (p - 0.12) / 0.73 }
        return 0.10 * (1 - p) / 0.15
    }

    /// D-243: the rise both rune layers share (the page's and the hero's): at `t`, a rune that rises over `duration`
    /// seconds led by `lead` stands at `y` on a ground `height` tall, turned `rotationDegrees`, at the envelope `opacity`.
    static func rise(_ t: Double, duration: Double, lead: Double, height h: Double) -> (y: Double, rotationDegrees: Double, opacity: Double) {
        let u = (t + lead) / duration
        let p = u - u.rounded(.down)
        return (h + 30 - p * h * 680 / 640, 14 * p, opacity(p))
    }

    static func pose(_ i: Int, _ t: Double, in size: CGSize) -> Pose {
        let (glyph, silver, x, s) = base(i, size)
        let r = rise(t, duration: Double(16 + (i * 7) % 14), lead: Double((i * 11) % 26), height: Double(size.height))
        return Pose(glyph: glyph, silver: silver, x: x, y: r.y, size: s, rotationDegrees: r.rotationDegrees, opacity: r.opacity)
    }

    /// The still frame: the Look Book's reduced-motion layout.
    static func still(_ i: Int, in size: CGSize) -> Pose {
        let (glyph, silver, x, s) = base(i, size)
        return Pose(glyph: glyph, silver: silver, x: x, y: Double(size.height) * (1 - Double((i * 41) % 90) / 100),
                    size: s, rotationDegrees: 0, opacity: 0.10)
    }
}

/// D-243 (U24, the founder's approved Vibrant mock-up, 2026-10-20; .drafts/d214/motion.py sections 5 and 6): Norse's runes
/// glow as they rise and flare now and then. The flare is applied after the rise (RuneDrift's poses do not move): a rune's
/// core is its rise envelope (the pose's opacity over `RuneDrift.peak`) times its rest (`RuneDrift.peak` x its layer's
/// strength) lifted toward `top` by the flare; its halo, a radial disc of `haloRadius` x its size, is `halo` at the top
/// of a flare and in proportion below it. The flare: 0 at rest, eased in and out (`ease`, each half its own segment, as
/// the mock-up's CSS keyframes) up from `rise` of its cycle to 1 at `top`, back to 0 at `fall`. Page rune i flares on a
/// `cycle(i)` s cycle led by `lead(i)` s; the hero's runes carry their own (`HeroRunes`). Still: the still pose's
/// opacity times the strength, no flare and no halo. Android's twin is `RuneGlow`; its ThemeMotionTest reads these.
enum RuneGlow {
    static let pageFlare = 0.19
    static let heroFlare = 0.20
    static let halo = 0.05
    static let haloRadius = 0.8
    static let rise = 0.62
    static let top = 0.76
    static let fall = 0.90
    static let ease: (Double, Double, Double, Double) = (0.42, 0.0, 0.58, 1.0)

    static func cycle(_ i: Int) -> Double { Double(7 + (i * 5) % 7) }
    static func lead(_ i: Int) -> Double { Double((i * 3) % 11) }

    /// The flare at `q`, a fraction of its cycle in 0..<1: 0 outside `rise`..<`fall`, 1 only at `top`.
    static func shape(_ q: Double, rise: Double = RuneGlow.rise, top: Double = RuneGlow.top, fall: Double = RuneGlow.fall) -> Double {
        if q < rise || q >= fall { return 0 }
        if q < top { return Bezier.y((q - rise) / (top - rise), ease) }
        return 1 - Bezier.y((q - top) / (fall - top), ease)
    }

    /// A flare on a `cycle` s cycle led by `lead` s, at `t`.
    static func flare(_ t: Double, cycle: Double, lead: Double) -> Double {
        let u = (t + lead) / cycle
        return shape(u - u.rounded(.down))
    }

    /// Page rune `i`'s flare at `t`.
    static func flare(_ i: Int, _ t: Double) -> Double { flare(t, cycle: cycle(i), lead: lead(i)) }

    /// The core's and the halo's alphas of a rune at its pose's `opacity`, its layer's `strength`, the layer's flare `top`
    /// and the flare `glow` (0...1). Still: no flare, no halo.
    static func alphas(opacity: Double, strength: Double, top: Double, glow: Double, still: Bool) -> (core: Double, halo: Double) {
        let envelope = opacity / RuneDrift.peak
        let rest = RuneDrift.peak * strength
        let core = envelope * (rest + (top - rest) * (still ? 0 : glow))
        return (core, still ? 0 : halo * core / top)
    }
}

/// D-243: the Norse hero's own rising runes: build.py's seven (j = 3...9, the page's formulas) in the mock-up's order,
/// each in an even lane LEFT of the ᚠ (`x` is the glyph's centre, a fraction of the hero's width), rising as the page's
/// runes do (`RuneDrift.rise`) on the hero's height, flaring on their own `cycle` led by `flareLead`, clipped to the inner
/// frame's interior. Still: `stillY` of the height, upright, at the still opacity 0.10. Drawn like the page's runes at
/// `Strength.of("norseHeroRunes")` toward `RuneGlow.heroFlare`. Android's twin is `HeroRunes.SPECS`.
enum HeroRunes {
    struct Spec: Equatable {
        let glyph: String
        let silver: Bool
        let x: Double
        let size: Double
        let duration: Double
        let lead: Double
        let cycle: Double
        let flareLead: Double
        let stillY: Double
    }

    static let specs: [Spec] = [
        Spec(glyph: "\u{16C3}", silver: false, x: 0.050, size: 25, duration: 23, lead: 3, cycle: 11, flareLead: 4, stillY: 0.75),
        Spec(glyph: "\u{16DC}", silver: true, x: 0.135, size: 15, duration: 23, lead: 7, cycle: 8, flareLead: 9, stillY: 0.67),
        Spec(glyph: "\u{16BA}", silver: false, x: 0.220, size: 26, duration: 16, lead: 10, cycle: 12, flareLead: 2, stillY: 0.42),
        Spec(glyph: "\u{16D6}", silver: true, x: 0.305, size: 16, duration: 16, lead: 14, cycle: 9, flareLead: 7, stillY: 0.34),
        Spec(glyph: "\u{16B1}", silver: false, x: 0.390, size: 20, duration: 16, lead: 18, cycle: 13, flareLead: 1, stillY: 0.26),
        Spec(glyph: "\u{16CA}", silver: true, x: 0.475, size: 17, duration: 23, lead: 21, cycle: 10, flareLead: 5, stillY: 0.91),
        Spec(glyph: "\u{16A2}", silver: false, x: 0.560, size: 21, duration: 23, lead: 25, cycle: 7, flareLead: 10, stillY: 0.83),
    ]

    static var count: Int { specs.count }

    static func pose(_ i: Int, _ t: Double, in size: CGSize) -> RuneDrift.Pose {
        let s = specs[i]
        let r = RuneDrift.rise(t, duration: s.duration, lead: s.lead, height: Double(size.height))
        return RuneDrift.Pose(glyph: s.glyph, silver: s.silver, x: Double(size.width) * s.x, y: r.y, size: s.size,
                              rotationDegrees: r.rotationDegrees, opacity: r.opacity)
    }

    static func still(_ i: Int, in size: CGSize) -> RuneDrift.Pose {
        let s = specs[i]
        return RuneDrift.Pose(glyph: s.glyph, silver: s.silver, x: Double(size.width) * s.x, y: Double(size.height) * s.stillY,
                              size: s.size, rotationDegrees: 0, opacity: 0.10)
    }

    static func flare(_ i: Int, _ t: Double) -> Double { RuneGlow.flare(t, cycle: specs[i].cycle, lead: specs[i].flareLead) }
}

/// D-243: the Norse hero's ᚠ glows now and then: `rest` (today's 0.16) up to `peak` on a `cycle` s cycle (rising from
/// `rise` of it, top at `top`, rest again at `fall`, each half `RuneGlow.ease`), with a radial halo of `haloRadius` x its
/// size centred on its box, `halo` at the top of the glow (motion.py 1c'': min(the floor's cap 0.145, 0.10)). Still: `rest`,
/// no halo, today's ᚠ. The renderer multiplies both by `Strength.of("norseHero")`. Android's twin is `WatermarkGlow`.
enum WatermarkGlow {
    static let rest = 0.16
    static let peak = 0.208
    static let cycle: Double = 9
    static let rise = 0.55
    static let top = 0.70
    static let fall = 0.88
    static let halo = 0.10
    static let haloRadius = 0.5

    static func glow(_ t: Double) -> Double {
        let u = t / cycle
        return RuneGlow.shape(u - u.rounded(.down), rise: rise, top: top, fall: fall)
    }

    static func alpha(_ t: Double, still: Bool) -> Double { still ? rest : rest + (peak - rest) * glow(t) }

    static func haloAlpha(_ t: Double, still: Bool) -> Double { still ? 0 : halo * glow(t) }
}

/// D-243: the Futhark ring round You's picture (D-221's RuneRing) turns clockwise, one turn per `period` seconds of
/// turning, from upright each time it starts; still (Reduce Motion, Less motion, power saving) it is upright. Each lit
/// rune carries a static radial halo of the ring colour at `litHalo`, radius `litHaloRadius` x the ring's rune size.
/// Android's twin is `RingTurn`.
enum RingTurn {
    static let period: Double = 90
    static let still: Double = 0
    static let litHalo = 0.35
    static let litHaloRadius = 0.5

    /// The ring's turn in degrees, clockwise, in 0..<360, after `seconds` of turning; a clock that is not finite is upright.
    static func degrees(_ seconds: Double) -> Double {
        guard seconds.isFinite else { return 0 }
        let u = seconds / period
        return 360 * (u - u.rounded(.down))
    }
}

/// D-226 (L34, .drafts/d226/art.py section 48): Norse's two page glows, the Look Book's radial gradients (LB 220): a
/// colour at `alpha` at the centre (`centreX`, `centreY`, fractions of the page) of an ellipse `radiusX` of the page's
/// width by `radiusY` of its height, clear at `clearAt` of the radius. Still (no motion); drawn over `Tokens.bg` and under
/// the runes, each alpha times `Strength.of("norsePageGlow")`. Android's twin is `NorseGlow.SPECS`; its ThemeMotionTest
/// reads this table.
enum NorseGlow {
    struct Spec: Equatable {
        let hex: String
        let alpha: Double
        let radiusX: Double
        let radiusY: Double
        let centreX: Double
        let centreY: Double
        let clearAt: Double
    }

    static let specs: [Spec] = [
        Spec(hex: ArtDraw.runeGoldHex, alpha: 0.09, radiusX: 0.90, radiusY: 0.60, centreX: 0.5, centreY: 0.0, clearAt: 0.70),
        Spec(hex: ThemeArt.hex["norseSilver"]!, alpha: 0.07, radiusX: 1.20, radiusY: 0.80, centreX: 0.5, centreY: 1.20, clearAt: 0.60),
    ]
}

/// Vardr Labs' currency signs drifting across the hero (the Look Book's seeded field).
enum CurrencyField {
    struct Floater: Equatable {
        let glyph: String
        let bx, by, ax, ay, sx, sy, ph: Double
        let size: Int
        let rot: Double
        let cyan: Bool
        let depth: Double
    }
    struct Pose: Equatable { let x: Double; let y: Double; let rotation: Double; let alpha: Double; let size: Double }

    static let signs = ["$", "\u{20B9}", "\u{20AC}", "\u{00A3}", "\u{00A5}", "\u{20A9}", "\u{20BD}", "\u{0E3F}", "\u{20BA}", "\u{20A6}", "\u{20B1}",
                        "\u{20AA}", "\u{20AB}", "\u{20B4}", "\u{5143}", "R$", "kr", "\u{00A2}", "\u{20B8}", "A$", "C$", "z\u{0142}"]
    /// The majors: (bx, by, size, cyan).
    static let majors: [String: (Double, Double, Int, Bool)] = [
        "\u{20B9}": (0.80, 0.32, 40, true), "$": (0.62, 0.68, 30, false), "\u{20AC}": (0.90, 0.72, 28, false),
        "\u{00A3}": (0.70, 0.18, 24, false), "\u{00A5}": (0.94, 0.40, 24, false),
    ]
    /// The still frame's time (the Look Book's reduced-motion frame).
    static let still: Double = 4

    /// The first `n` draws of Park-Miller from seed 7.
    static func draws(_ n: Int) -> [Double] {
        var seed: Int64 = 7
        return (0..<n).map { _ in
            seed = (seed * 16807) % 2_147_483_647
            return Double(seed - 1) / 2_147_483_646
        }
    }

    static let floaters: [Floater] = {
        let r = draws(signs.count * 10)
        return signs.enumerated().map { i, g in
            let d = Array(r[(i * 10)..<(i * 10 + 10)])
            var f = Floater(glyph: g, bx: 0.06 + d[0] * 0.9, by: 0.1 + d[1] * 0.8, ax: 0.02 + d[2] * 0.05, ay: 0.03 + d[3] * 0.07,
                            sx: 0.15 + d[4] * 0.25, sy: 0.12 + d[5] * 0.22, ph: d[6] * 6.283, size: 16 + Int((d[7] * 26).rounded()),
                            rot: (d[8] - 0.5) * 0.5, cyan: i % 5 == 0, depth: 0.35 + d[9] * 0.65)
            if let m = majors[g] {
                f = Floater(glyph: g, bx: m.0, by: m.1, ax: 0.025, ay: 0.05, sx: f.sx, sy: f.sy, ph: f.ph, size: m.2,
                            rot: f.rot, cyan: m.3 || f.cyan, depth: 1)
            }
            return f
        }
    }()

    static func pose(_ f: Floater, _ t: Double) -> Pose {
        let x = f.bx + f.ax * sin(t * f.sx + f.ph)
        let y = f.by + f.ay * cos(t * f.sy + f.ph * 1.3)
        let calm = min(1, max(0.28, (x - 0.2) / 0.45))
        return Pose(x: x, y: y, rotation: f.rot + 0.12 * sin(t * 0.3 + f.ph), alpha: (0.16 + 0.5 * f.depth) * calm,
                    size: (Double(f.size) * (0.7 + 0.5 * f.depth)).rounded())
    }
}

/// The hex bolt turns 30 degrees while pressed (Vardr Labs only), never under Reduce Motion.
enum AddButtonMotion {
    static let turnDegrees: Double = 30
    /// The turn's own spring (the Look Book's `.45s cubic-bezier(.3,1.4,.5,1)`): response 0.45 s, damping 0.55.
    /// Android's twin is `spring(dampingRatio = 0.55f, stiffness = 195f)`, stiffness = (2 pi / 0.45) squared.
    static let turnResponse = 0.45
    static let turnDamping = 0.55

    static func turn(art: AddButtonArt, pressed: Bool, reduceMotion: Bool) -> Double {
        art == .bolt && pressed && !reduceMotion ? turnDegrees : 0
    }
}

/// D-221 (E4, L45): the settled celebration's burst (the Look Book's `celebrate`), a pure function of the time since it
/// started; .drafts/d221/motion.py part 5 ports it. Distances are the Look Book's px over its 280 px screen, scaled to
/// the page's width. The renderer (D-221 Task 8) draws what `pose` returns; Android's ThemeMotionTest reads these values.
enum SettledBurst {
    /// From the origin (`originX`, `originY` of the page) in points, y down; scale and opacity of the particle.
    struct Pose: Equatable { let dx: Double; let dy: Double; let scale: Double; let opacity: Double }

    static let count = 26
    static let originX = 0.5
    static let originY = 0.46
    static let lbScreen = 280.0
    static let baseDistance = 70.0
    static let distanceStep = 18.0
    static let outward = 1.25
    static let drop = 20.0
    static let duration = 1.5
    static let durationStep = 0.12
    static let peakAt = 0.55
    static let startScale = 0.4
    static let endScale = 0.8
    /// The Look Book's own 6.283, not 2 pi.
    static let angleStep = 6.283
    /// CSS `cubic-bezier(.2,.7,.3,1)`.
    static let ease: (Double, Double, Double, Double) = (0.2, 0.7, 0.3, 1.0)
    static let auroraDot = 8.0
    static let auroraGlow = 10.0
    static let norseRune = 22.0
    static let vardrSquare = 4.0

    /// Particle `i` flies for this long (seconds). (Named apart from the `duration` constant, which Android's parity
    /// test reads.)
    static func particleDuration(_ i: Int) -> Double { duration + Double(i % 4) * durationStep }

    /// The CSS cubic-bezier `ease` at progress `u` (D-224: through the shared solver, `Bezier.y`).
    static func eased(_ u: Double) -> Double { Bezier.y(u, ease) }

    /// Particle `i` at `elapsed` seconds on a page `width` points wide; nil once it has finished. The keyframes sit at
    /// 0, `peakAt` and 1 of the eased progress: out to its distance growing to full size and opacity, then a quarter
    /// further, `drop` lower, shrinking to `endScale` and fading out.
    static func pose(_ i: Int, elapsed: Double, width: Double) -> Pose? {
        let d = particleDuration(i)
        guard elapsed < d else { return nil }
        let p = eased(max(0, elapsed / d))
        let angle = Double(i) / Double(count) * angleStep
        let distance = (baseDistance + Double(i % 5) * distanceStep) / lbScreen * width
        let fall = drop / lbScreen * width
        let r: Double, lower: Double, scale: Double, opacity: Double
        if p <= peakAt {
            let f = p / peakAt
            (r, lower, scale, opacity) = (distance * f, 0, startScale + (1 - startScale) * f, f)
        } else {
            let f = (p - peakAt) / (1 - peakAt)
            (r, lower, scale, opacity) = (distance * (1 + (outward - 1) * f), fall * f, 1 - (1 - endScale) * f, 1 - f)
        }
        return Pose(dx: cos(angle) * r, dy: sin(angle) * r + lower, scale: scale, opacity: opacity)
    }
}

/// D-221 (L45): the celebration's timeline (seconds; `dimTo` the page content's opacity; `messageRise` points;
/// `messageTop` the words' centre as a fraction of the page's height).
enum CelebrationTiming {
    static let total = 3.6
    static let dimTo = 0.22
    static let dimIn = 0.3
    static let messageIn = 0.4
    static let messageRise = 10.0
    static let messageTop = 0.42
}

// MARK: - D-224 (E3): the done moments

/// D-224: the CSS cubic-bezier, one solver for every eased motion (.drafts/d224/motion.py `bezier`): x(s) = u solved by
/// bisection (48 halvings), then y(s). `p` is (x1, y1, x2, y2); y may overshoot (the check's pop).
enum Bezier {
    static func y(_ u: Double, _ p: (Double, Double, Double, Double)) -> Double {
        if u <= 0 { return 0 }
        if u >= 1 { return 1 }
        func coord(_ s: Double, _ a: Double, _ b: Double) -> Double {
            3 * a * s * (1 - s) * (1 - s) + 3 * b * s * s * (1 - s) + s * s * s
        }
        var (lo, hi) = (0.0, 1.0)
        for _ in 0..<48 {
            let mid = (lo + hi) / 2
            if coord(mid, p.0, p.2) < u { lo = mid } else { hi = mid }
        }
        return coord((lo + hi) / 2, p.1, p.3)
    }

    /// The eased progress of a segment that starts at `start` and lasts `duration`, clamped to 0...1 before easing.
    static func segment(_ elapsed: Double, start: Double, duration: Double, _ p: (Double, Double, Double, Double)) -> Double {
        y(min(max((elapsed - start) / duration, 0), 1), p)
    }
}

/// D-224 (L49): the big done check's draw, a pure function of the time since the moment started (.drafts/d224/motion.py
/// part 6, the Look Book's `.check` and `.runecircle`). The circle's trim runs from 12 o'clock clockwise, the tick's from
/// its first point; the dashes are the Look Book's (302 and 60 over lengths a little shorter, so each closes early). The
/// pop eases each half with the same curve (CSS applies the timing to each keyframe segment), so it overshoots.
enum CheckMotion {
    static let circleDraw = 0.55
    static let circleEase: (Double, Double, Double, Double) = (0.6, 0.0, 0.2, 1.0)
    static let circleDash = 302.0
    static let tickAt = 0.45
    static let tickDraw = 0.35
    static let tickEase: (Double, Double, Double, Double) = (0.3, 0.0, 0.2, 1.0)
    static let tickDash = 60.0
    static let popAt = 0.7
    static let popDuration = 0.5
    static let popScale = 1.08
    static let popEase: (Double, Double, Double, Double) = (0.3, 1.6, 0.5, 1.0)
    /// Seconds per turn of Norse's rune ring, clockwise, linear.
    static let runeTurn = 14.0
    /// The still frame (Reduce Motion or Less motion): everything drawn, the pop over, the ring a whole turn round.
    static let stillElapsed = 14.0
    /// D-239 review (I1): the moment the pop is over. A check that lands and then pauses (power saving stills Norse's
    /// ring) holds this pose, so the ring stops where it is instead of jumping back upright.
    static var landedElapsed: Double { popAt + popDuration }

    /// The circle's length in the check's box (radius `doneCircleRadius`).
    static var circleLength: Double { 2 * Double.pi * (ThemeArt.metrics["doneCircleRadius"] ?? 0) }
    /// The tick's length through `DoneCheck.tick`.
    static var tickLength: Double {
        zip(DoneCheck.tick, DoneCheck.tick.dropFirst()).reduce(0) { sum, pair in
            sum + hypot(pair.1.0 - pair.0.0, pair.1.1 - pair.0.1)
        }
    }

    /// (circle trim 0...1, tick trim 0...1, scale, the rune ring's degrees clockwise) at `elapsed` seconds.
    static func pose(_ elapsed: Double) -> (circle: Double, tick: Double, scale: Double, degrees: Double) {
        let circle = min(1, circleDash * Bezier.segment(elapsed, start: 0, duration: circleDraw, circleEase) / circleLength)
        let tick = min(1, tickDash * Bezier.segment(elapsed, start: tickAt, duration: tickDraw, tickEase) / tickLength)
        let half = popDuration / 2
        let scale: Double
        if elapsed <= popAt || elapsed >= popAt + popDuration {
            scale = 1
        } else if elapsed < popAt + half {
            scale = 1 + (popScale - 1) * Bezier.segment(elapsed, start: popAt, duration: half, popEase)
        } else {
            scale = popScale + (1 - popScale) * Bezier.segment(elapsed, start: popAt + half, duration: half, popEase)
        }
        let turned = (360 * elapsed / runeTurn).truncatingRemainder(dividingBy: 360)
        return (circle, tick, scale, turned < 0 ? turned + 360 : turned)
    }
}

/// D-224 (L50): the small done toast's motion (.drafts/d224/motion.py part 6): in over `fadeIn` (opacity 0 to 1, rising
/// `rise` points, eased by `ease`), held, out over the last `fadeOut` (opacity, linear), gone at `total`. Still (Reduce
/// Motion or Less motion; D-239: never power saving): shown whole for `total`, then gone. A new moment replaces it at once (no queue).
enum ToastMotion {
    static let fadeIn = 0.2
    static let fadeOut = 0.25
    static let total = 3.0
    static let rise = 10.0
    static let ease: (Double, Double, Double, Double) = (0.2, 0.7, 0.3, 1.0)

    /// (opacity, dy in points below the rest place), or nil before it starts and once it has gone.
    static func pose(elapsed: Double, still: Bool) -> (opacity: Double, dy: Double)? {
        guard elapsed >= 0, elapsed < total else { return nil }
        if still { return (1, 0) }
        if elapsed < fadeIn {
            let p = Bezier.y(elapsed / fadeIn, ease)
            return (p, rise * (1 - p))
        }
        if elapsed > total - fadeOut { return ((total - elapsed) / fadeOut, 0) }
        return (1, 0)
    }
}

/// D-224 (Design 3): when a done moment's success haptic plays, in seconds after it is posted. A small moment at once;
/// a big one when the check lands (the pop's start), or at once when still.
enum DoneHapticTiming {
    static func delay(big: Bool, still: Bool) -> Double {
        guard big else { return 0 }
        return still ? 0 : CheckMotion.popAt
    }
}

/// D-224 (Design 3): a success haptic within `window` seconds of the previous one is skipped (a settle that also
/// completes the group would otherwise buzz twice).
enum HapticGate {
    static let window = 1.0

    static func allows(lastSuccess: Double?, now: Double) -> Bool {
        guard let lastSuccess else { return true }
        return now - lastSuccess >= window
    }
}

/// D-224: a moment's elapsed time on the art's clock: the time since it started while ticking (and finite), else the
/// still frame's own time (`CheckMotion.stillElapsed` for the check; the toast handles its still mode itself).
enum MomentClock {
    static func elapsed(tick: Bool, now: Double, startedAt: Double, still: Double) -> Double {
        let elapsed = now - startedAt
        return tick && elapsed.isFinite ? elapsed : still
    }
}

// MARK: - Task 3: the renderer

/// Low Power Mode, observed (the spec's still mode).
@Observable @MainActor
final class PowerState {
    static let shared = PowerState()
    var lowPower: Bool = ProcessInfo.processInfo.isLowPowerModeEnabled
    @ObservationIgnored private var token: NSObjectProtocol?

    init() {
        token = NotificationCenter.default.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled }
        }
    }
}

extension MotionSetting {
    /// D-231 (decision 71): the motion input outside a view (a done moment posted from code): the phone's flag read
    /// now, or the switch. The one imperative read of the phone's setting in the app (ThemeMotionTests' guard).
    @MainActor
    static func reducedNow() -> Bool {
        reduced(system: UIAccessibility.isReduceMotionEnabled, lessMotion: AppearanceState.shared.lessMotion)
    }
}

private struct MotionReducedKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    /// D-231 (decision 71): the app's one motion input, the phone's Reduce Motion or the person's Less motion, set at
    /// the root by `AppearanceApplier`. Every view that honours Reduce Motion reads this, never the phone's own value.
    var motionReduced: Bool {
        get { self[MotionReducedKey.self] }
        set { self[MotionReducedKey.self] = newValue }
    }
}

private struct ArtVisibleKey: EnvironmentKey { static let defaultValue = true }

extension EnvironmentValues {
    /// False for a tab kept alive but not shown (HomeShell's `tabPage`): its art does not tick.
    var artVisible: Bool {
        get { self[ArtVisibleKey.self] }
        set { self[ArtVisibleKey.self] = newValue }
    }
}

/// The art's timeline (P2, fix wave 3): every canvas in the window updates on ONE grid, the multiples of 1 / 30 s
/// counted from the reference date, so two arts on one screen (a page and its hero) redraw on the same frame and the
/// window renders 30 times a second. An `.animation(minimumInterval:)` schedule counts from the moment its own view
/// appeared, so two of them could fall on alternating frames (measured: BUILD-LOG D-214). Paused, it has no entries:
/// the canvas draws once and stays still. Android's twin is FrameGate's shared grid.
struct ArtSchedule: TimelineSchedule {
    let paused: Bool

    /// The grid's index of the first update at or after `date` (a whole number; the update is at index / fps).
    static func firstSlot(atOrAfter date: Date) -> Double {
        (date.timeIntervalSinceReferenceDate * MotionClock.fps).rounded(.up)
    }

    func entries(from startDate: Date, mode: TimelineScheduleMode) -> Entries {
        Entries(slot: Self.firstSlot(atOrAfter: startDate), paused: paused)
    }

    struct Entries: Sequence, IteratorProtocol {
        var slot: Double
        let paused: Bool

        mutating func next() -> Date? {
            guard !paused else { return nil }
            defer { slot += 1 }
            return Date(timeIntervalSinceReferenceDate: slot / MotionClock.fps)
        }
    }
}

/// One renderer for all moving art: a Canvas redrawn on the art's shared 30 fps grid (ArtSchedule), paused (drawing
/// the still frame) when shouldTick says so. Decorative: hidden from VoiceOver and from touches. `symbols` are views
/// the draw reuses by tag (P3, fix wave 3): SwiftUI renders each once and again only when it changes (its size, the
/// theme, the mode), so a frame draws them without laying out any text.
struct ArtCanvas<Symbols: View>: View {
    let still: Double
    /// D-239 (decision 137): a moment drawn on the art's canvas (the settle burst) ticks under power saving too.
    let moment: Bool
    let symbols: Symbols
    let draw: (inout GraphicsContext, CGSize, Double) -> Void

    init(still: Double, moment: Bool = false, @ViewBuilder symbols: () -> Symbols,
         draw: @escaping (inout GraphicsContext, CGSize, Double) -> Void) {
        self.still = still
        self.moment = moment
        self.symbols = symbols()
        self.draw = draw
    }

    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    @Environment(\.artVisible) private var visible
    @State private var onScreen = false

    /// The time the engine is handed: always finite (Curtains.pose traps on NaN or infinity). Paused draws the still
    /// frame; a clock that is not finite falls back to it too, and the still itself falls back to 0.
    static func seconds(tick: Bool, now: Double, still: Double) -> Double {
        let safeStill = still.isFinite ? still : 0
        return tick && now.isFinite ? now : safeStill
    }

    var body: some View {
        let active = phase == .active && visible && onScreen
        let tick = moment ? MotionClock.momentTicks(reduceMotion: reduceMotion, active: active)
            : MotionClock.shouldTick(reduceMotion: reduceMotion, lowPower: PowerState.shared.lowPower, active: active)
        TimelineView(ArtSchedule(paused: !tick)) { timeline in
            Canvas { ctx, size in
                draw(&ctx, size, Self.seconds(tick: tick, now: timeline.date.timeIntervalSinceReferenceDate, still: still))
            } symbols: {
                symbols
            }
        }
        .onAppear { onScreen = true }
        .onDisappear { onScreen = false }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

extension ArtCanvas where Symbols == EmptyView {
    init(still: Double, draw: @escaping (inout GraphicsContext, CGSize, Double) -> Void) {
        self.init(still: still, symbols: { EmptyView() }, draw: draw)
    }
}

/// The drawing of each art layer, shared by the page grounds and the heroes.
enum ArtDraw {
    private static func color(_ s: Curtains.Stop) -> Color {
        Color(red: s.r / 255, green: s.g / 255, blue: s.b / 255).opacity(s.alpha)
    }

    /// The night sky or the dawn is the caller's; this draws the three curtains, then the stars (night) over them.
    static func curtains(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, night: Bool,
                         curtainStrength: Double, starStrength: Double) {
        // One blur for the three curtains (the twin of Android's one layer, one blur): they blend among themselves
        // inside the layer, and the layer lands on the ground source-over. Dithered, so the faint tails do not band.
        var outer = ctx
        outer.addFilter(.blur(radius: night ? 9 : 14, options: .dithersResult))
        outer.drawLayer { layer in
            for (i, spec) in Curtains.specs.enumerated() {
                let p = Curtains.pose(i, t)
                var c = layer
                c.blendMode = night ? .screen : .multiply
                c.opacity = (night ? spec.nightOpacity : spec.lightOpacity) * curtainStrength
                let w = size.width * p.width, h = size.height * p.height
                // The curtain's own transform: to its place, then the sway's scale and skew about its centre.
                c.translateBy(x: size.width * p.x + w / 2, y: size.height * p.y + h / 2)
                c.concatenate(CGAffineTransform(a: p.sx, b: 0, c: tan(p.skewDegrees * .pi / 180) * p.sx, d: p.sy, tx: 0, ty: 0))
                c.translateBy(x: -w / 2, y: -h / 2)
                let shading = GraphicsContext.Shading.linearGradient(
                    Gradient(stops: spec.stops.map { .init(color: color($0), location: $0.at) }),
                    startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: h))
                let shape = hem(w, h)
                // The streaks: the arched curtain at the pattern's base, then its bright bars, clipped to the arch too.
                var base = c
                base.opacity *= Curtains.streakBase
                base.fill(shape, with: shading)
                var bars = Path()
                var x: CGFloat = 0
                while x < w {
                    for bar in Curtains.streakBars { bars.addRect(CGRect(x: x + bar.x, y: 0, width: bar.width, height: h)) }
                    x += Curtains.streakPitch
                }
                var bright = c
                bright.opacity *= Curtains.streakBar
                bright.clip(to: bars)
                bright.fill(shape, with: shading)
            }
        }
        // D-226 (Q7 a, L33): the stars after the curtain layer, source-over on the unblurred context, so they shine
        // through the curtains as the Look Book's do (drawn first, the curtains' screen veiled them). Page and hero alike.
        if night && starStrength > 0 {
            for s in Stars.all {
                let r = CGFloat(s.radius)
                ctx.fill(Path(ellipseIn: CGRect(x: size.width * s.x - r, y: size.height * s.y - r, width: r * 2, height: r * 2)),
                         with: .color(.white.opacity(s.alpha * starStrength)))
            }
        }
    }

    /// A w x h curtain's arched outline (Curtains.hemCurves).
    static func hem(_ w: CGFloat, _ h: CGFloat) -> Path {
        var path = Path()
        path.move(to: .zero)
        path.addLine(to: CGPoint(x: w, y: 0))
        path.addLine(to: CGPoint(x: w, y: h * Curtains.hemTop))
        for k in Curtains.hemCurves(w, h) {
            path.addCurve(to: CGPoint(x: k[4], y: k[5]), control1: CGPoint(x: k[0], y: k[1]), control2: CGPoint(x: k[2], y: k[3]))
        }
        path.closeSubpath()
        return path
    }

    /// The rune colours: the Look Book's gold `#CDA750` and silver `#A9AEB6` (its `.runefall span` and `span.s`), made
    /// vibrant by D-242 (U24, the founder's "Vibrant"; .drafts/d214/motion.py GOLD, SILVER). The gold is the Norse accent.
    static let runeGoldHex = "#DFB043"
    static let runeSilverHex = "#B8C0CC"
    private static let runeGold = Color(UIColor(hex: runeGoldHex))
    private static let runeSilver = Color(UIColor(hex: runeSilverHex))

    /// Rune i as a symbol (tag i): its glyph at its size in its colour, none of which moves (P3: laid out once).
    static func runeSymbol(_ i: Int) -> some View {
        glyphSymbol(RuneDrift.still(i, in: .zero), tag: i)
    }

    /// D-243: a rune's glyph as a symbol (the page's and the hero's runes alike).
    private static func glyphSymbol(_ p: RuneDrift.Pose, tag: Int) -> some View {
        Text(verbatim: p.glyph).font(TypeRole.rune(p.size)).foregroundColor(p.silver ? runeSilver : runeGold).tag(tag)
    }

    /// D-243: symbol tags. Page runes are 0..<16 (D-214's), their halos `pageHalo + i`; the hero's runes `heroRune + i`,
    /// their halos `heroHalo + i`; the ᚠ `fehu`, its halo `fehuHalo`. Each ForEach of a canvas's symbols iterates its own
    /// tag range (its ids are the tags): two ForEach over the same ids trap in SwiftUI's CanvasSymbols (seen on launch).
    static let pageHalo = 100, heroRune = 200, heroHalo = 300, fehu = 400, fehuHalo = 401

    /// D-243: a halo as a symbol (laid out once, never blurred): a disc of `radius` in `colour`, full at its centre and
    /// clear at its edge, linearly (motion.py's halo). The draw sets its alpha.
    static func haloSymbol(tag: Int, colour: Color, radius: CGFloat) -> some View {
        Circle()
            .fill(RadialGradient(colors: [colour, colour.opacity(0)], center: .center, startRadius: 0, endRadius: radius))
            .frame(width: radius * 2, height: radius * 2)
            .tag(tag)
    }

    /// D-243: page rune i's halo, `RuneGlow.haloRadius` x its size, in its colour.
    static func runeHaloSymbol(_ i: Int) -> some View {
        let p = RuneDrift.still(i, in: .zero)
        return haloSymbol(tag: pageHalo + i, colour: p.silver ? runeSilver : runeGold, radius: RuneGlow.haloRadius * p.size)
    }

    /// D-243: the hero's rune i as a symbol, and its halo.
    static func heroRuneSymbol(_ i: Int) -> some View {
        glyphSymbol(HeroRunes.still(i, in: .zero), tag: heroRune + i)
    }

    static func heroHaloSymbol(_ i: Int) -> some View {
        let p = HeroRunes.still(i, in: .zero)
        return haloSymbol(tag: heroHalo + i, colour: p.silver ? runeSilver : runeGold, radius: RuneGlow.haloRadius * p.size)
    }

    /// D-243: the hero's ᚠ as a symbol (D-226's watermark, in the accent at full strength: the draw sets its alpha), and its
    /// halo, `WatermarkGlow.haloRadius` x its size, centred on its box.
    static func fehuSymbol() -> some View {
        Text(verbatim: Runes.balance)
            .font(TypeRole.rune(ThemeArt.m("heroWatermark")))
            .foregroundColor(Tokens.accent)
            .tag(fehu)
    }

    static func fehuHaloSymbol() -> some View {
        haloSymbol(tag: fehuHalo, colour: Tokens.accent, radius: WatermarkGlow.haloRadius * ThemeArt.m("heroWatermark"))
    }

    /// Each rune is drawn from its symbol (`runeSymbol`), centred on its pose. D-243: at `RuneGlow.alphas`' core over its
    /// halo (the flare toward `RuneGlow.pageFlare`); still: the still pose at 0.10 x the strength, no flare and no halo.
    static func runes(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, still: Bool, strength: Double) {
        for i in 0..<RuneDrift.count {
            let p = still ? RuneDrift.still(i, in: size) : RuneDrift.pose(i, t, in: size)
            let glow = RuneGlow.alphas(opacity: p.opacity, strength: strength, top: RuneGlow.pageFlare,
                                       glow: still ? 0 : RuneGlow.flare(i, t), still: still)
            glowingRune(&ctx, p, core: glow.core, halo: glow.halo, glyph: i, haloTag: pageHalo + i)
        }
    }

    /// D-243: the Norse hero's own runes (`HeroRunes`), drawn as the page's are, at `norseHeroRunes` toward
    /// `RuneGlow.heroFlare`, clipped to the inner frame's interior (inset `heroFrameInset` + 1, the frame's 1 pt line).
    static func heroRunes(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, still: Bool) {
        let inset = ThemeArt.m("heroFrameInset") + 1
        var inner = ctx
        inner.clip(to: Path(CGRect(origin: .zero, size: size).insetBy(dx: inset, dy: inset)))
        let strength = Strength.of("norseHeroRunes")
        for i in 0..<HeroRunes.count {
            let p = still ? HeroRunes.still(i, in: size) : HeroRunes.pose(i, t, in: size)
            let glow = RuneGlow.alphas(opacity: p.opacity, strength: strength, top: RuneGlow.heroFlare,
                                       glow: still ? 0 : HeroRunes.flare(i, t), still: still)
            glowingRune(&inner, p, core: glow.core, halo: glow.halo, glyph: heroRune + i, haloTag: heroHalo + i)
        }
    }

    /// D-243: a rune at its pose: its halo (a cached disc, centred on it) at `halo`, then its glyph at `core`.
    private static func glowingRune(_ ctx: inout GraphicsContext, _ p: RuneDrift.Pose, core: Double, halo: Double,
                                    glyph: Int, haloTag: Int) {
        guard core > 0, let symbol = ctx.resolveSymbol(id: glyph) else { return }
        if halo > 0, let disc = ctx.resolveSymbol(id: haloTag) {
            var under = ctx
            under.opacity = halo
            under.draw(disc, at: CGPoint(x: p.x, y: p.y))
        }
        var layer = ctx
        layer.opacity = core
        layer.translateBy(x: p.x, y: p.y)
        layer.rotate(by: .degrees(p.rotationDegrees))
        layer.draw(symbol, at: .zero)
    }

    /// D-243: the hero's ᚠ where D-226's Text stood (its box's trailing edge `heroWatermarkTrailing` in, its top at
    /// `heroWatermarkTop`), at `WatermarkGlow.alpha` x `norseHero`, over its halo while it glows. Still: 0.16, no halo
    /// (today's ᚠ).
    static func watermark(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, still: Bool) {
        guard let glyph = ctx.resolveSymbol(id: fehu) else { return }
        let strength = Strength.of("norseHero")
        // The box's origin on the device-pixel grid, as D-226's pixel-snapped Text stood (fix wave M1).
        let scale = max(ctx.environment.displayScale, 1)
        let left = ((size.width - ThemeArt.m("heroWatermarkTrailing") - glyph.size.width) * scale).rounded() / scale
        let top = (ThemeArt.m("heroWatermarkTop") * scale).rounded() / scale
        let centre = CGPoint(x: left + glyph.size.width / 2, y: top + glyph.size.height / 2)
        let halo = WatermarkGlow.haloAlpha(t, still: still) * strength
        if halo > 0, let disc = ctx.resolveSymbol(id: fehuHalo) {
            var under = ctx
            under.opacity = halo
            under.draw(disc, at: centre)
        }
        var layer = ctx
        layer.opacity = WatermarkGlow.alpha(t, still: still) * strength
        layer.draw(glyph, at: centre)
    }

    /// Sign i of the currency field as a symbol (tag i) for a hero `width` wide: its glyph at its size (the size never
    /// moves: CurrencyField.pose's size depends on the floater alone) in its colour (P3: laid out once per width).
    static func signSymbol(_ i: Int, width: CGFloat) -> some View {
        let f = CurrencyField.floaters[i]
        let points = CurrencyField.pose(f, CurrencyField.still).size * width / 560
        let font = Font(TypeRole.uiFont(TypeRole.plexMedium, size: points, weight: nil, tabular: false))
        return Text(verbatim: f.glyph).font(font).foregroundColor(f.cyan ? Tokens.ring : Tokens.accent).tag(i)
    }

    /// Each sign is drawn from its symbol (`signSymbol`), centred on its pose.
    static func signs(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, strength: Double) {
        for (i, f) in CurrencyField.floaters.enumerated() {
            let p = CurrencyField.pose(f, t)
            guard let sign = ctx.resolveSymbol(id: i) else { continue }
            var layer = ctx
            layer.opacity = p.alpha * strength
            layer.translateBy(x: size.width * p.x, y: size.height * p.y)
            layer.rotate(by: .radians(p.rotation))
            layer.draw(sign, at: .zero)
        }
    }
}

/// The page ground per theme: Aurora's dawn (light) or night (dark) with the curtains, Norse's rising runes,
/// Vardr Labs' still dot grid (D-213).
struct PageArt: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let theme = AppearanceState.shared.theme
        let dark = scheme == .dark
        Group {
            switch Ground.of(theme) {
            case .curtains:
                ZStack {
                    if dark { NightSky() } else { Dawn() }
                    ArtCanvas(still: Curtains.still) { ctx, size, t in
                        ArtDraw.curtains(&ctx, size, t, night: dark,
                                         curtainStrength: Strength.of(dark ? "auroraDarkPageCurtains" : "auroraLightPageCurtains"),
                                         starStrength: dark ? Strength.of("auroraDarkPageStars") : 0)
                    }
                }
            case .runes:
                ZStack {
                    Tokens.bg
                    NorseGlowView()
                    RuneLayer()
                }
            case .grid:
                ZStack { Tokens.bg; VardrGrid() }
            }
        }
        .ignoresSafeArea()
    }
}

/// Norse's runes: the Look Book's reduced-motion layout when the motion inputs are off. D-243: the page's sixteen
/// (`place: .page`, the default) or the Norse hero's seven and its ᚠ (`place: .hero`), each glowing; one read of power
/// saving for both.
struct RuneLayer: View {
    enum Place { case page, hero }

    var place: Place = .page
    @Environment(\.motionReduced) private var reduceMotion

    /// Still exactly when Reduce Motion or Low Power is on; when only the screen is inactive nothing is seen.
    static func isStill(reduceMotion: Bool, lowPower: Bool) -> Bool { reduceMotion || lowPower }

    var body: some View {
        let still = Self.isStill(reduceMotion: reduceMotion, lowPower: PowerState.shared.lowPower)
        switch place {
        case .page:
            ArtCanvas(still: 0, symbols: {
                ForEach(0..<RuneDrift.count, id: \.self) { ArtDraw.runeSymbol($0) }
                ForEach(ArtDraw.pageHalo..<ArtDraw.pageHalo + RuneDrift.count, id: \.self) { ArtDraw.runeHaloSymbol($0 - ArtDraw.pageHalo) }
            }) { ctx, size, t in
                ArtDraw.runes(&ctx, size, t, still: still, strength: Strength.of("norsePageRunes"))
            }
        case .hero:
            ArtCanvas(still: 0, symbols: {
                ForEach(ArtDraw.heroRune..<ArtDraw.heroRune + HeroRunes.count, id: \.self) { ArtDraw.heroRuneSymbol($0 - ArtDraw.heroRune) }
                ForEach(ArtDraw.heroHalo..<ArtDraw.heroHalo + HeroRunes.count, id: \.self) { ArtDraw.heroHaloSymbol($0 - ArtDraw.heroHalo) }
                ArtDraw.fehuSymbol()
                ArtDraw.fehuHaloSymbol()
            }) { ctx, size, t in
                ArtDraw.heroRunes(&ctx, size, t, still: still)
                ArtDraw.watermark(&ctx, size, t, still: still)
            }
        }
    }
}

/// D-243: a piece of art that turns (You's rune ring, `RingTurn`), on the art's clock: clockwise from upright each time
/// it starts ticking, upright while still (Reduce Motion, Less motion, power saving through `MotionClock.artTicks`, or
/// not on an active screen). Only the content turns (a rotation, no layout); decorative.
struct ArtTurn<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) { self.content = content() }

    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    @Environment(\.artVisible) private var visible
    @State private var onScreen = false
    /// When this run of ticking began; nil while still.
    @State private var since: Double?

    var body: some View {
        let tick = MotionClock.artTicks(reduceMotion: reduceMotion, active: phase == .active && visible && onScreen)
        TimelineView(ArtSchedule(paused: !tick)) { timeline in
            let now = timeline.date.timeIntervalSinceReferenceDate
            content.rotationEffect(.degrees(RingTurn.degrees(
                MomentClock.elapsed(tick: tick, now: now, startedAt: since ?? now, still: RingTurn.still))))
        }
        .onChange(of: tick, initial: true) { _, ticking in since = ticking ? Date().timeIntervalSinceReferenceDate : nil }
        .onAppear { onScreen = true }
        .onDisappear { onScreen = false }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

/// D-226 (L34, .drafts/d226/art.py section 48): Norse's two page glows (`NorseGlow.specs`), still, over the page colour
/// and under the runes. Dawn's technique: the context scaled to the ellipse's radii, a UNIT radial gradient clear at the
/// table's stop, so the falloff is elliptical as the Look Book's `radial-gradient(<rx>% <ry>% at ...)`.
struct NorseGlowView: View {
    var body: some View {
        Canvas { ctx, size in
            let strength = Strength.of("norsePageGlow")
            for spec in NorseGlow.specs {
                let colour = Color(UIColor(hex: spec.hex))
                var g = ctx
                g.translateBy(x: size.width * spec.centreX, y: size.height * spec.centreY)
                g.scaleBy(x: size.width * spec.radiusX, y: size.height * spec.radiusY)
                g.fill(Path(CGRect(x: -spec.clearAt, y: -spec.clearAt, width: spec.clearAt * 2, height: spec.clearAt * 2)),
                       with: .radialGradient(Gradient(colors: [colour.opacity(spec.alpha * strength), colour.opacity(0)]),
                                             center: .zero, startRadius: 0, endRadius: spec.clearAt))
            }
        }
        .accessibilityHidden(true)
    }
}

struct NightSky: View {
    var body: some View {
        LinearGradient(stops: [.init(color: Color(UIColor(hex: "#03070D")), location: 0),
                               .init(color: Color(UIColor(hex: "#071321")), location: 0.55),
                               .init(color: Color(UIColor(hex: "#0A1B26")), location: 1)],
                       startPoint: .top, endPoint: .bottom)
    }
}

/// Aurora light's arctic dawn: a pale vertical wash with a glow rising from above the top edge. The glow is the Look
/// Book's `radial-gradient(ellipse 120% 60% at 50% -10%, #D2F0E5, transparent 70%)`, drawn in a Canvas by scaling the
/// context to the ellipse's radii (1.2 x width, 0.6 x height) and filling a UNIT radial gradient that reaches clear at
/// 0.70 of the radius, so the falloff is elliptical (a circular RadialGradient in a stretched frame would not be).
struct Dawn: View {
    var body: some View {
        Canvas { ctx, size in
            let wash = Gradient(stops: [.init(color: Color(UIColor(hex: "#E1F1EC")), location: 0),
                                        .init(color: Color(UIColor(hex: "#EEF5F3")), location: 0.55),
                                        .init(color: Color(UIColor(hex: "#EEF5F3")), location: 1)])
            ctx.fill(Path(CGRect(origin: .zero, size: size)),
                     with: .linearGradient(wash, startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            let glow = Color(UIColor(hex: "#D2F0E5"))
            var g = ctx
            g.translateBy(x: size.width * 0.5, y: -size.height * 0.10)
            g.scaleBy(x: size.width * 1.20, y: size.height * 0.60)
            g.fill(Path(CGRect(x: -2, y: -2, width: 4, height: 4)),
                   with: .radialGradient(Gradient(colors: [glow, glow.opacity(0)]), center: .zero, startRadius: 0, endRadius: 0.70))
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Task 5: the heroes

/// D-214: a hero's art per theme, drawn behind its content (the caller clips the art, not its content, to the hero's shape):
/// Aurora: the night sky with stars and curtains (always night, at the founder's full strength); Norse: the gradient,
/// its own rising runes and the glowing ᚠ watermark (D-243, `RuneLayer(place: .hero)`) and the inner frame; Vardr Labs:
/// the flat ground, the frame marks and the drifting currency signs.
struct HeroArt: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let theme = AppearanceState.shared.theme
        let dark = scheme == .dark
        // Decoration as a whole: hidden from VoiceOver and from touches in every theme (the caller clips it).
        Group {
            switch theme {
            case .aurora:
                ZStack {
                    Tokens.heroGradient
                    ArtCanvas(still: Curtains.still) { ctx, size, t in
                        ArtDraw.curtains(&ctx, size, t, night: true, curtainStrength: Strength.of("auroraHeroCurtains"),
                                         starStrength: Strength.of("auroraHeroStars"))
                    }
                }
            case .norse:
                GeometryReader { geo in
                    // D-226 (L31, Q8 a): the Look Book's two stops at CSS 160 degrees for the hero's own size; the
                    // watermark and the inner frame at the art scale (art.py 46), from the table both phones read.
                    let ends = CssGradient.unitPoints(degrees: 160, width: geo.size.width, height: geo.size.height)
                    ZStack(alignment: .topTrailing) {
                        LinearGradient(colors: [Tokens.heroStart, Tokens.heroEnd], startPoint: ends.start, endPoint: ends.end)
                        // D-243: the hero's own rising runes and the glowing ᚠ (ArtDraw.heroRunes, ArtDraw.watermark: the
                        // ᚠ at heroWatermark, heroWatermarkTrailing in, its top at heroWatermarkTop, as D-226's Text).
                        RuneLayer(place: .hero)
                        Rectangle().strokeBorder(Tokens.accent.opacity(0.25 * Strength.of("norseHero")), lineWidth: 1)
                            .padding(ThemeArt.m("heroFrameInset"))
                        // D-226 fix wave (I1, decision 29): the edge on the notch's straight sides only, 1 pt inside and
                        // clipped, as a Norse card's and Android's `notchEdge`; the diagonals stay unlit.
                        NotchEdges(chamfer: ThemeArt.m("chamfer")).stroke(ThemeArt.norseEdge, lineWidth: 1).clipShape(ThemeArt.cardShape(.norse))
                    }
                    .frame(width: geo.size.width, height: geo.size.height)
                }
            case .vardr:
                ZStack {
                    Tokens.heroGradient
                    FrameMarks(inset: ThemeArt.m("frameInset"), length: ThemeArt.m("frameLength"))
                        .stroke(Tokens.textDim.opacity(Double(ThemeArt.m("frameAlpha"))), lineWidth: 1)
                    GeometryReader { geo in
                        let width = geo.size.width
                        ArtCanvas(still: CurrencyField.still, symbols: {
                            ForEach(CurrencyField.floaters.indices, id: \.self) { ArtDraw.signSymbol($0, width: width) }
                        }) { ctx, size, t in
                            ArtDraw.signs(&ctx, size, t, strength: Strength.of(dark ? "vardrDarkHeroSigns" : "vardrLightHeroSigns"))
                        }
                    }
                }
                .overlay(Rectangle().strokeBorder(Tokens.hairline, lineWidth: 1))
                .overlay { if !dark { Rectangle().strokeBorder(Tokens.accentSoft, lineWidth: 1).padding(1) } }
            }
        }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

// MARK: - D-221 Task 8: the settled burst

/// D-221 (L45): the settled celebration's particles (SettledBurst's poses), drawn by `CelebrationOverlay`'s ArtCanvas.
/// Android's twin is `ArtDraw.burst` in ThemeMotion.kt.
extension ArtDraw {
    /// Aurora's glow: `ring` at this alpha at the dot's centre, clear at its edge (Android's radial gradient).
    static let burstGlowAlpha = 0.55

    /// Norse's rune i of the Futhark as a symbol (tag i), at `norseRune` in `ring`: laid out once (P3), drawn by every
    /// particle that carries it.
    static func burstRuneSymbol(_ i: Int) -> some View {
        Text(verbatim: Runes.futhark[i]).font(TypeRole.rune(SettledBurst.norseRune)).foregroundColor(Tokens.ring).tag(i)
    }

    /// The symbols the burst draws: Norse's 24 runes; the dots and squares are paths.
    @ViewBuilder
    static func burstSymbols(_ theme: AppTheme) -> some View {
        if theme == .norse { ForEach(Runes.futhark.indices, id: \.self) { burstRuneSymbol($0) } }
    }

    /// The burst `elapsed` seconds after the play started: particle i from (originX, originY) of the page at its pose
    /// (offset, scale, opacity), in the theme's `ring`: Aurora a dot with a glow, Norse rune i % 24 of the Futhark,
    /// Vardr Labs a square. Nothing before the start (a paused canvas hands its still time, 0, so elapsed is far
    /// below 0), with a clock that is not finite, or once every particle has finished.
    static func burst(_ ctx: inout GraphicsContext, _ size: CGSize, _ elapsed: Double, theme: AppTheme) {
        guard elapsed.isFinite, elapsed >= 0 else { return }
        let origin = CGPoint(x: size.width * SettledBurst.originX, y: size.height * SettledBurst.originY)
        for i in 0..<SettledBurst.count {
            guard let p = SettledBurst.pose(i, elapsed: elapsed, width: Double(size.width)), p.opacity > 0 else { continue }
            let at = CGPoint(x: origin.x + p.dx, y: origin.y + p.dy)
            var layer = ctx
            layer.opacity = p.opacity
            switch theme {
            case .aurora: burstDot(&layer, at, p.scale)
            case .norse: burstRune(&layer, i, at, p.scale)
            case .vardr: burstSquare(&layer, at, p.scale)
            }
        }
    }

    /// Aurora's particle: a dot of `auroraDot` with a glow `auroraGlow` beyond it, both scaled with the particle.
    private static func burstDot(_ ctx: inout GraphicsContext, _ at: CGPoint, _ scale: Double) {
        let dot = SettledBurst.auroraDot / 2 * scale
        let glow = dot + SettledBurst.auroraGlow * scale
        ctx.fill(Path(ellipseIn: CGRect(x: at.x - glow, y: at.y - glow, width: glow * 2, height: glow * 2)),
                 with: .radialGradient(Gradient(colors: [Tokens.ring.opacity(burstGlowAlpha), Tokens.ring.opacity(0)]),
                                       center: at, startRadius: 0, endRadius: glow))
        ctx.fill(Path(ellipseIn: CGRect(x: at.x - dot, y: at.y - dot, width: dot * 2, height: dot * 2)), with: .color(Tokens.ring))
    }

    /// Norse's particle: its rune's symbol, centred on its place and scaled about its centre.
    private static func burstRune(_ ctx: inout GraphicsContext, _ i: Int, _ at: CGPoint, _ scale: Double) {
        guard let glyph = ctx.resolveSymbol(id: i % Runes.futhark.count) else { return }
        ctx.translateBy(x: at.x, y: at.y)
        ctx.scaleBy(x: scale, y: scale)
        ctx.draw(glyph, at: .zero)
    }

    /// Vardr Labs' particle: a square of `vardrSquare`, scaled with the particle.
    private static func burstSquare(_ ctx: inout GraphicsContext, _ at: CGPoint, _ scale: Double) {
        let side = SettledBurst.vardrSquare * scale
        ctx.fill(Path(CGRect(x: at.x - side / 2, y: at.y - side / 2, width: side, height: side)), with: .color(Tokens.ring))
    }
}
