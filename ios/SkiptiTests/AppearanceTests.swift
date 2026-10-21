import SwiftUI
import UIKit
import XCTest
@testable import Skipti

/// D-212 (ADR-0041): three themes by light and dark, the text size, and the
/// floors every pair must clear in every set. Android's twin is AppearanceTest.
final class AppearanceTests: XCTestCase {
    private func traits(_ set: ThemeSet) -> UITraitCollection {
        UITraitCollection { t in
            t.userInterfaceStyle = set.isDark ? .dark : .light
            t[ThemeTrait.self] = set.theme
        }
    }

    private func hex(_ color: UIColor) -> String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X", Int(round(r * 255)), Int(round(g * 255)), Int(round(b * 255)))
    }

    private func luminance(_ color: UIColor) -> Double {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        func channel(_ c: CGFloat) -> Double {
            let v = Double(c)
            return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private func contrast(_ a: UIColor, _ b: UIColor) -> Double {
        let (hi, lo) = (max(luminance(a), luminance(b)), min(luminance(a), luminance(b)))
        return (hi + 0.05) / (lo + 0.05)
    }

    /// D-214 (the founder, 2026-10-15): the moving art's floors hold on 8-bit pixels, so each colour is rounded to
    /// 1/255 a channel before the ratio (motion.py's `q8`).
    private func q8(_ c: UIColor) -> UIColor {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        func q(_ v: CGFloat) -> CGFloat { (v * 255).rounded() / 255 }
        return UIColor(red: q(r), green: q(g), blue: q(b), alpha: 1)
    }

    private func contrast8(_ a: UIColor, _ b: UIColor) -> Double { contrast(q8(a), q8(b)) }

    /// `fg` at `alpha` laid over `ground`, as the eye sees it.
    private func over(_ fg: UIColor, _ alpha: CGFloat, _ ground: UIColor) -> UIColor {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        fg.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        ground.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(red: r2 + (r1 - r2) * alpha, green: g2 + (g1 - g2) * alpha, blue: b2 + (b1 - b2) * alpha, alpha: 1)
    }

    /// Every token resolves its own set's value under that set's traits.
    /// Goldens from the Look Book via .drafts/d212/palette.py.
    func testEveryTokenResolvesItsSetsValue() {
        for set in ThemeSet.allCases {
            for (name, column) in Tokens.palette {
                XCTAssertEqual(hex(Tokens.uiColor(name).resolvedColor(with: traits(set))), Tokens.hex(column, set), "\(set) \(name)")
            }
        }
        XCTAssertEqual(hex(Tokens.uiColor("bg").resolvedColor(with: traits(.auroraLight))), "#EEF5F3")
        XCTAssertEqual(hex(Tokens.uiColor("bg").resolvedColor(with: traits(.norse))), "#07080E")
        XCTAssertEqual(hex(Tokens.uiColor("accent").resolvedColor(with: traits(.vardrDark))), "#9DB0BF")
        XCTAssertEqual(ThemeSet.resolve(.norse, dark: false), .norse, "Norse has no light set")
    }

    /// D-242 (U24, the founder's "Vibrant", 2026-10-20): the palette and the group tones are the scripts' table, copied
    /// from .drafts/d220/palette.py --swift (groundFaint and groundAccent by D-214/D-220/D-226's rules: Norse takes textDim
    /// and the accent, art.py section 48), never read back. D-242 moved the Norse column only: Aurora's and Vardr Labs'
    /// columns are the ones they had before it, so a change to any of them fails here.
    func testThePaletteIsTheScriptsTableAndOnlyNorseMoved() {
        let want: [String: Tokens.Column] = [
            "bg": ("#EEF5F3", "#050A12", "#07080E", "#E8ECEF", "#0B0F13"),
            "surface": ("#FFFFFF", "#101A24", "#111218", "#EEF1F4", "#0E141A"),
            "surfaceRaised": ("#F6FAF9", "#1D2730", "#1F1F23", "#EBEEF2", "#1B2127"),
            "hairline": ("#D5E3DF", "#1D2C38", "#4A3B1B", "#C3CDD4", "#1D2731"),
            "text": ("#0E1B1A", "#E6F5F0", "#F3EAD3", "#182430", "#E9EEF3"),
            "textDim": ("#4D615E", "#8FA9A4", "#B5A888", "#46555F", "#8FA0AE"),
            "textFaint": ("#607370", "#7A918E", "#9A8F76", "#5E6C75", "#7A8995"),
            "owed": ("#842D2A", "#EC7D79", "#EE8A58", "#9A4D16", "#E89055"),
            "owing": ("#356F2F", "#97C87E", "#7FD4C1", "#0A6C92", "#5AD0F2"),
            "neutral": ("#607370", "#7A918E", "#9A8F76", "#5E6C75", "#7A8995"),
            "watch": ("#7A611B", "#E8C170", "#E8C170", "#7A611B", "#E8C170"),
            "accent": ("#0B7A5F", "#45E6B0", "#DFB043", "#4C6172", "#9DB0BF"),
            "groundFaint": ("#4D615E", "#8FA9A4", "#B5A888", "#5E6C75", "#7A8995"),
            "groundAccent": ("#4D615E", "#45E6B0", "#DFB043", "#475B6B", "#9DB0BF"),
            "onAccent": ("#FFFFFF", "#04140F", "#130D02", "#EEF1F4", "#0B0F13"),
            "accentSoft": ("#DDF2EA", "#10302A", "#2A2211", "#DFE5E9", "#121A21"),
            "owingSoft": ("#EBF1EA", "#283934", "#253536", "#D7E4EA", "#1C3641"),
            "owedSoft": ("#F3EAEA", "#382C33", "#392824", "#E6E1DE", "#352A25"),
            "ring": ("#0C8A6C", "#45E6B0", "#DFB043", "#0A6D93", "#5AD0F2"),
            "ringTrack": ("#D5E3DF", "#1D2C38", "#2B2A2E", "#C3CDD4", "#1D2731"),
            "meterStart": ("#0C8A6C", "#1FB6C9", "#9AA2AE", "#4C6172", "#9DB0BF"),
            "meterEnd": ("#2BC9A0", "#45E6B0", "#DFB043", "#0A6D93", "#5AD0F2"),
            "heroStart": ("#03070D", "#03070D", "#211A0A", "#EEF1F4", "#0B0F13"),
            "heroMid": ("#071321", "#071321", "#16120C", "#EEF1F4", "#0B0F13"),
            "heroEnd": ("#0A1B26", "#0A1B26", "#0A0A0F", "#EEF1F4", "#0B0F13"),
            "onHero": ("#EAFBF4", "#EAFBF4", "#F3EAD3", "#182430", "#E9EEF3"),
        ]
        XCTAssertEqual(Set(Tokens.palette.keys), Set(want.keys))
        func cols(_ c: Tokens.Column) -> [String] { [c.auroraLight, c.auroraDark, c.norse, c.vardrLight, c.vardrDark] }
        for (name, column) in want { XCTAssertEqual(cols(Tokens.palette[name]!), cols(column), name) }
        let tones: [GroupTone: (fill: Tokens.Column, letter: Tokens.Column)] = [
            .coral: (fill: ("#FFE4DA", "#3A2119", "#261F21", "#DDDADA", "#2C2829"), letter: ("#B53F18", "#FF9C78", "#E1AA96", "#875342", "#DAAD9D")),
            .blue: (fill: ("#E3EBFC", "#17223D", "#1E212C", "#D7DCE4", "#262B34"), letter: ("#2F5BBF", "#93B4FF", "#ABBDE7", "#4F6089", "#B1C0E1")),
            .teal: (fill: ("#DDF5F0", "#12302B", "#172125", "#D1DBDD", "#1E2A2C"), letter: ("#0B7A6B", "#5FD6C4", "#7ABBB1", "#2A5B55", "#80B5AD")),
            .amber: (fill: ("#FFF1D6", "#33270F", "#23201E", "#D8D9D7", "#2A2926"), letter: ("#8A5A00", "#F2C063", "#D2B683", "#644E26", "#CBB48A")),
            .sky: (fill: ("#DFF1FB", "#10293A", "#1B222A", "#D2DBE0", "#222B31"), letter: ("#0B6A96", "#7CC9F0", "#96C0D6", "#315C70", "#9CBFD0")),
            .pink: (fill: ("#FFE3F0", "#3A1A2A", "#261E27", "#DCD9DF", "#2D282F"), letter: ("#A8286A", "#FF8CC2", "#E5A6C4", "#854B69", "#DFACC4")),
        ]
        XCTAssertEqual(tones.count, GroupTone.allCases.count)
        for (tone, w) in tones {
            XCTAssertEqual(cols(tone.column.fill), cols(w.fill), "\(tone) fill")
            XCTAssertEqual(cols(tone.column.letter), cols(w.letter), "\(tone) letter")
        }
    }

    /// D-248 (U29.4): Add expense's "0" hint sits on the page ground in `groundFaint` at full opacity (never the system's
    /// placeholder colour, never dimmed): 4.5:1 on the page in every set. The negative: Android's old hint, `textFaint` at
    /// 45%, came to about 2:1 on Norse's page.
    func testTheAmountHintIsGroundFaintAtFullOpacityOnThePage() throws {
        for set in ThemeSet.allCases {
            func c(_ name: String) -> UIColor { Tokens.uiColor(name).resolvedColor(with: traits(set)) }
            XCTAssertGreaterThanOrEqual(contrast(c("groundFaint"), c("bg")), 4.5, "\(set): the hint on the page")
        }
        let norse = traits(.norse)
        let old = over(Tokens.uiColor("textFaint").resolvedColor(with: norse), 0.45, Tokens.uiColor("bg").resolvedColor(with: norse))
        XCTAssertLessThan(contrast(old, Tokens.uiColor("bg").resolvedColor(with: norse)), 4.5, "the old 45% hint fails")
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/AddExpenseScreen.swift")
        let add = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(add.contains("TextField(text: $amountText, prompt: Text(verbatim: AmountEntry.hint).foregroundStyle(Tokens.groundFaint)) {"))
        XCTAssertFalse(add.contains("TextField(\"0\""), "no system placeholder")
        XCTAssertEqual(AmountEntry.hint, "0")
    }

    /// The floors of ADR-0036 and ADR-0038, extended by ADR-0041 to every set.
    func testEveryPairClearsItsContrastFloorInEveryThemeAndMode() {
        for set in ThemeSet.allCases {
            func c(_ name: String) -> UIColor { Tokens.uiColor(name).resolvedColor(with: traits(set)) }
            var grounds = ["bg", "surface", "surfaceRaised"].map { ($0, c($0)) }
            if set.theme.hasGlass {
                grounds += [("glass78", over(c("surface"), 0.78, c("bg"))), ("glass82", over(c("surface"), 0.82, c("bg")))]
            }
            let floors: [(String, Double)] = [
                ("text", 7), ("textDim", 4.5), ("textFaint", 4.5), ("owed", 4.5), ("owing", 4.5),
                ("neutral", 4.5), ("watch", 4.5), ("accent", 4.5), ("groundFaint", 4.5), ("groundAccent", 4.5),
            ]
            for (fg, floor) in floors {
                for (name, ground) in grounds {
                    let ratio = contrast(c(fg), ground)
                    XCTAssertGreaterThanOrEqual(ratio, floor, "\(set): \(fg) on \(name) is \(ratio)")
                }
            }
            for (fg, fill) in [("onAccent", "accent"), ("owing", "owingSoft"), ("owed", "owedSoft"), ("accent", "accentSoft")] {
                let ratio = contrast(c(fg), c(fill))
                XCTAssertGreaterThanOrEqual(ratio, 4.5, "\(set): \(fg) on \(fill) is \(ratio)")
            }
            XCTAssertGreaterThanOrEqual(contrast(c("ring"), c("bg")), 3, "\(set): ring")
            for stop in ["heroStart", "heroMid", "heroEnd"] {
                let ground = c(stop)
                XCTAssertGreaterThanOrEqual(contrast(c("onHero"), ground), 7, "\(set): onHero on \(stop)")
                XCTAssertGreaterThanOrEqual(contrast(over(c("onHero"), 0.82, ground), ground), 4.5, "\(set): dim hero text on \(stop)")
                let film = over(c("onHero"), CGFloat(Aurora.heroChipOpacity), ground)
                XCTAssertGreaterThanOrEqual(contrast(c("onHero"), film), 4.5, "\(set): hero chip on \(stop)")
            }
            XCTAssertGreaterThanOrEqual(contrast(c("heroStart"), c("onHero")), 4.5, "\(set): the hero's Settle up label")
        }
    }

    /// D-214: the brightest pixel the moving art can reach, each part at its strongest over each ground stop, measured on
    /// 8-bit pixels (the founder, 2026-10-15). The curtains stacked as drawn: testTheStackedCurtainsKeepTheAuroraPageFloors.
    func testTheMovingArtKeepsEveryFloorItCovers() {
        func c(_ n: String, _ s: ThemeSet) -> UIColor { Tokens.uiColor(n).resolvedColor(with: traits(s)) }
        func rgb(_ s: Curtains.Stop) -> UIColor { UIColor(red: s.r / 255, green: s.g / 255, blue: s.b / 255, alpha: 1) }
        func channels(_ x: UIColor) -> (CGFloat, CGFloat, CGFloat) {
            var (r, g, b, a): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
            x.getRed(&r, green: &g, blue: &b, alpha: &a)
            return (r, g, b)
        }
        func screen(_ fg: UIColor, _ a: CGFloat, _ g: UIColor) -> UIColor {
            let (r1, g1, b1) = channels(fg), (r2, g2, b2) = channels(g)
            func s(_ x: CGFloat, _ y: CGFloat) -> CGFloat { y + ((1 - (1 - x) * (1 - y)) - y) * a }
            return UIColor(red: s(r1, r2), green: s(g1, g2), blue: s(b1, b2), alpha: 1)
        }
        func multiply(_ fg: UIColor, _ a: CGFloat, _ g: UIColor) -> UIColor {
            let (r1, g1, b1) = channels(fg), (r2, g2, b2) = channels(g)
            func m(_ x: CGFloat, _ y: CGFloat) -> CGFloat { y + (x * y - y) * a }
            return UIColor(red: m(r1, r2), green: m(g1, g2), blue: m(b1, b2), alpha: 1)
        }
        let night = ["#03070D", "#071321", "#0A1B26"].map { UIColor(hex: $0) }
        let dawn = ["#D2F0E5", "#E1F1EC", "#EEF5F3"].map { UIColor(hex: $0) }
        func curtainComposites(_ grounds: [UIColor], night isNight: Bool, _ k: Double) -> [UIColor] {
            Curtains.specs.flatMap { spec in spec.stops.flatMap { stop in grounds.map { g in
                let a = CGFloat(stop.alpha * (isNight ? spec.nightOpacity : spec.lightOpacity) * k)
                return isNight ? screen(rgb(stop), a, g) : multiply(rgb(stop), a, g)
            } } }
        }
        func starComposites(_ grounds: [UIColor], _ k: Double) -> [UIColor] {
            Stars.all.flatMap { s in grounds.map { over(.white, CGFloat(s.alpha * k), $0) } }
        }
        // Aurora pages: text 7:1; labels, groundFaint and groundAccent 4.5:1 (the founder, 2026-10-15).
        let darkPage = curtainComposites(night, night: true, Strength.of("auroraDarkPageCurtains"))
            + starComposites(night, Strength.of("auroraDarkPageStars"))
        let lightPage = curtainComposites(dawn, night: false, Strength.of("auroraLightPageCurtains")) + dawn
        for (set, grounds) in [(ThemeSet.auroraDark, darkPage), (.auroraLight, lightPage)] {
            for g in grounds {
                XCTAssertGreaterThanOrEqual(contrast8(c("text", set), g), 7, "\(set) text on the page art")
                for n in ["textDim", "groundFaint", "groundAccent"] {
                    XCTAssertGreaterThanOrEqual(contrast8(c(n, set), g), 4.5, "\(set) \(n) on the page art")
                }
            }
        }
        // Aurora heroes: full strength by the founder's decision (2026-10-15), not floored; pinned here so a change is seen.
        XCTAssertEqual(Strength.of("auroraHeroCurtains"), 1.0)
        XCTAssertEqual(Strength.of("auroraHeroStars"), 1.0)
        // Norse page (D-226, .drafts/d226/art.py section 48): the ground is bg and each glow's brightest on-screen pixel
        // (the screen point nearest the ellipse's centre, the alpha falling to clear at `clearAt` of the radius: gold
        // 0.09 at the top centre (D-242; 0.08 before), silver 0.07 x (1 - 0.25 / 0.60) = 0.0408 at the bottom), and a gold and a silver rune
        // at their peak over each. Faint words on the Norse page ground take groundFaint (D-226): textFaint leaves this
        // block (a rune over the gold peak leaves it at 4.17; 3.91 before D-242; art.py section 48).
        let bg = c("bg", .norse)
        let glowPeaks = NorseGlow.specs.map { s -> Double in
            let (px, py) = (min(max(s.centreX, 0), 1), min(max(s.centreY, 0), 1))
            let d = hypot((s.centreX - px) / s.radiusX, (s.centreY - py) / s.radiusY)
            return s.alpha * max(0, 1 - d / s.clearAt) * Strength.of("norsePageGlow")
        }
        XCTAssertEqual(glowPeaks.count, 2)
        XCTAssertEqual(glowPeaks.first ?? -1, 0.09, accuracy: 1e-9, "the gold glow's peak")
        XCTAssertEqual(glowPeaks.last ?? -1, 0.0408, accuracy: 1e-4, "the silver glow's peak")
        let norseGrounds = [bg] + zip(NorseGlow.specs, glowPeaks).map { over(UIColor(hex: $0.hex), CGFloat($1), bg) }
        var norsePage = norseGrounds
        for g in norseGrounds {
            for colour in [UIColor(hex: ArtDraw.runeGoldHex), UIColor(hex: ArtDraw.runeSilverHex)] {
                norsePage.append(over(colour, CGFloat(RuneDrift.peak * Strength.of("norsePageRunes")), g))
            }
        }
        for g in norsePage {
            XCTAssertGreaterThanOrEqual(contrast8(c("text", .norse), g), 7)
            for n in ["textDim", "accent", "groundFaint", "groundAccent"] {
                XCTAssertGreaterThanOrEqual(contrast8(c(n, .norse), g), 4.5, "norse \(n)")
            }
        }
        // The worst of each, pinned beside Aurora's (art.py section 48, 8-bit).
        for (n, want) in [("text", 11.12), ("textDim", 5.66), ("groundFaint", 5.66), ("groundAccent", 6.62)] {
            let worst = norsePage.map { contrast8(c(n, .norse), $0) }.min() ?? 0
            XCTAssertEqual(worst, want, accuracy: 0.01, "norse \(n) on the page art: art.py's value")
        }
        // Norse hero: the watermark and the frame. S1 (fix wave 3): the hero's dim line is a fixed colour, onHero at
        // 0.82 over the hero's first stop (motion.py's `over(on, 0.82, stops[0])`), measured against the art.
        let norseDim = over(c("onHero", .norse), 0.82, c("heroStart", .norse))
        for g in ["heroStart", "heroMid", "heroEnd"].map({ c($0, .norse) }) {
            for a in [0.16, 0.25] {
                let art = over(c("accent", .norse), CGFloat(a * Strength.of("norseHero")), g)
                XCTAssertGreaterThanOrEqual(contrast8(c("onHero", .norse), art), 7, "norse hero text on the art at \(a)")
                XCTAssertGreaterThanOrEqual(contrast8(norseDim, art), 4.5, "norse hero dim text on the art at \(a)")
            }
        }
        // D-243 (.drafts/d214/motion.py section 5, 1c, 1c', 1c''): the art at the top of its glow. A page rune at the top of
        // its flare (RuneGlow.pageFlare) over its glow over each Norse ground, gold and silver; a hero rune at heroFlare over
        // its glow over each hero stop; the ᚠ at its glow (WatermarkGlow.peak) over its own. D-246 (amended on purpose;
        // D-243's was the disc halo's alpha): a glow's pixel is its alpha x the blurred mask's peak (motion.py 5a, the floors'
        // model: the peak under the full core), page 0.120 x 0.3430 (D-246 fix wave, I1: 0.145 before; 1e's cap with the
        // receiving rune's measured ink), hero 0.345 x 0.2657, the ᚠ 0.305 x 0.2319. The worst of each pinned to motion.py's
        // printout (8-bit, .drafts/d246/motion-after2.out); textDim's headroom is 0.104 here, 0.023 in 1e (below).
        let runeColours = [UIColor(hex: ArtDraw.runeGoldHex), UIColor(hex: ArtDraw.runeSilverHex)]
        let pageGlowPixel = RuneGlow.pageGlow * GlyphGlowGoldens.page, heroGlowPixel = RuneGlow.heroGlow * GlyphGlowGoldens.hero
        XCTAssertEqual(pageGlowPixel, 0.0412, accuracy: 0.0001, "motion.py 5a: the page glow's largest pixel")
        XCTAssertEqual(heroGlowPixel, 0.0917, accuracy: 0.0001, "motion.py 5a: the hero glow's largest pixel")
        func flaring(_ core: Double, _ glow: Double, _ grounds: [UIColor]) -> [UIColor] {
            grounds.flatMap { g in runeColours.map { over($0, CGFloat(core), over($0, CGFloat(glow), g)) } }
        }
        let flarePage = flaring(RuneGlow.pageFlare, pageGlowPixel, norseGrounds)
        for (n, floor, want) in [("text", 7.0, 9.043), ("textDim", 4.5, 4.604), ("groundFaint", 4.5, 4.604),
                                 ("accent", 4.5, 5.382), ("groundAccent", 4.5, 5.382)] {
            let worst = flarePage.map { contrast8(c(n, .norse), $0) }.min() ?? 0
            XCTAssertGreaterThanOrEqual(worst, floor, "norse \(n) over a flaring rune")
            XCTAssertEqual(worst, want, accuracy: 0.002, "norse \(n) over a flaring rune: motion.py's value")
        }
        // The negative: the least flare over the cap that fails (D-246 fix wave: 0.205 over the 0.0412 glow pixel; 0.20 over
        // 0.0497 before) leaves the labels under their floor (motion.py 1c: 4.459).
        let tooBright = flaring(RuneGlow.pageFlare + 0.015, pageGlowPixel, norseGrounds).map { contrast8(c("textDim", .norse), $0) }.min() ?? 9
        XCTAssertLessThan(tooBright, 4.5)
        XCTAssertEqual(tooBright, 4.459, accuracy: 0.002)
        // D-243 (T7, motion.py 1f, plan test 9): Norse's card-edge glow (accent at a band's alpha) over a flaring rune,
        // page text beside a card. D-246 fix wave (amended on purpose: with the page glow at 0.145 every band failed, so the
        // clear distance was the glow's whole reach): over the 0.0412 glow pixel the bands to 3.75 pt fail textDim (3.934 at
        // the first, 4.458 at 3.75) and those from 4.25 pt pass (4.523), so motion.py's CLEAR DISTANCE is 4.00 pt; the rule
        // keeps 6 pt, the glow's whole reach (.drafts/d243/gap-audit.md), past which the pixel is the flaring rune alone (4.604).
        func banded(_ alpha: Double) -> Double {
            flarePage.map { contrast8(c("textDim", .norse), over(c("accent", .norse), CGFloat(alpha), $0)) }.min() ?? 0
        }
        let bands = ArtGeometry.glowBands()
        for b in bands {
            if b.offset < 4 {
                XCTAssertLessThan(banded(b.alpha), 4.5, "the band at \(b.offset) pt fails textDim")
            } else {
                XCTAssertGreaterThanOrEqual(banded(b.alpha), 4.5, "the band at \(b.offset) pt, past the clear distance")
            }
        }
        XCTAssertEqual(banded(bands.first?.alpha ?? 0), 3.934, accuracy: 0.002, "the first band: motion.py's value")
        XCTAssertEqual(banded(bands[7].alpha), 4.458, accuracy: 0.002, "the band before the clear distance (3.75 pt)")
        XCTAssertEqual(bands[7].offset, 3.75, accuracy: 1e-9)
        XCTAssertEqual(banded(bands[8].alpha), 4.523, accuracy: 0.002, "the first band past it (4.25 pt)")
        XCTAssertGreaterThanOrEqual(banded(0), 4.5, "past the glow's reach")
        XCTAssertEqual(banded(0), 4.604, accuracy: 0.002)
        XCTAssertEqual(ArtGeometry.glowReach, CGFloat(bands.count) * ThemeArt.m("norseEdgeGlowBand"))
        XCTAssertEqual(ArtGeometry.glowReach, 6, accuracy: 1e-12, "motion.py's CLEAR DISTANCE")
        let heroStops = ["heroStart", "heroMid", "heroEnd"].map { c($0, .norse) }
        let heroRunes = flaring(RuneGlow.heroFlare, heroGlowPixel, heroStops)
        let fehu = heroStops.map { g in
            over(c("accent", .norse), CGFloat(WatermarkGlow.peak * Strength.of("norseHero")),
                 over(c("accent", .norse), CGFloat(WatermarkGlow.glyphGlow * GlyphGlowGoldens.fehu * Strength.of("norseHero")), g))
        }
        // The negative: a hero glow one step over motion.py 5a's cap (0.515), 0.52, takes onHero under 7 over a flaring
        // hero rune (motion.py: 6.955).
        let heroTooBright = flaring(RuneGlow.heroFlare, 0.52 * GlyphGlowGoldens.hero, heroStops)
            .map { contrast8(c("onHero", .norse), $0) }.min() ?? 9
        XCTAssertLessThan(heroTooBright, 7, "over the hero's cap the floor breaks")
        XCTAssertEqual(heroTooBright, 6.955, accuracy: 0.002)
        // D-246 fix wave (I1, M4): a hero rune at heroFlare over its own glow over the most glow its MEASURED ink takes from
        // the other rows at their lanes, each at its flare's top (motion.py 1d's lane bound, 0.011468; ThemeMotionTests'
        // hero table proves the bound from the lanes), gold or silver: onHero 7.498, dim 5.222.
        let laneRunes = heroStops.flatMap { g in
            runeColours.flatMap { col in
                runeColours.map { other in
                    over(col, CGFloat(RuneGlow.heroFlare), over(col, CGFloat(heroGlowPixel), over(other, CGFloat(GlyphGlowGoldens.heroLaneGlow), g)))
                }
            }
        }
        for (name, pixels, onWant, dimWant) in [("a hero rune", heroRunes, 7.738, 5.389), ("the glowing ᚠ", fehu, 8.107, 5.646),
                                                ("a hero rune over its neighbours' lane glow", laneRunes, 7.498, 5.222)] {
            let on = pixels.map { contrast8(c("onHero", .norse), $0) }.min() ?? 0
            let dim = pixels.map { contrast8(norseDim, $0) }.min() ?? 0
            XCTAssertGreaterThanOrEqual(on, 7, "norse hero text over \(name)")
            XCTAssertGreaterThanOrEqual(dim, 4.5, "norse hero dim text over \(name)")
            XCTAssertEqual(on, onWant, accuracy: 0.002, "\(name): motion.py's value")
            XCTAssertEqual(dim, dimWant, accuracy: 0.002, "\(name), dim: motion.py's value")
        }
        // Vardr Labs heroes: a sign at its peak in the steel (accent) or cyan (ring): the field's own brightest alpha,
        // (0.16 + 0.5 x depth 1) x calm 1 = 0.66, read off CurrencyField over 200 s so a change there follows.
        let signPeak = CurrencyField.floaters.flatMap { f in (0...400).map { CurrencyField.pose(f, Double($0) * 0.5).alpha } }.max() ?? 1
        XCTAssertEqual(signPeak, 0.66, accuracy: 1e-9)
        for (set, key) in [(ThemeSet.vardrLight, "vardrLightHeroSigns"), (.vardrDark, "vardrDarkHeroSigns")] {
            let dim = over(c("onHero", set), 0.82, c("heroStart", set))
            for g in ["heroStart", "heroMid", "heroEnd"].map({ c($0, set) }) {
                for n in ["accent", "ring"] {
                    let art = over(c(n, set), CGFloat(signPeak * Strength.of(key)), g)
                    XCTAssertGreaterThanOrEqual(contrast8(c("onHero", set), art), 7, "\(set) \(n) sign")
                    XCTAssertGreaterThanOrEqual(contrast8(dim, art), 4.5, "\(set) \(n) sign, dim")
                }
            }
        }
    }

    /// D-246 fix wave (I1): motion.py 1e's worst frames, recomputed by the shell from its own poses, alphas and page glow.
    /// Page runes 0 and 5 pass in neighbouring lanes around t 1426.2 s, rune 0 at the top of its flare under rune 5's glow;
    /// motion.py's whole-hour scan finds every page's worst there. Each rune's neighbours shed glow x min(peak,
    /// Phi(-(r - inkPage s_b) / (blur s_b))) on it, r the distance less the RECEIVING rune's measured ink (0.4569 x size;
    /// D-243's 0.30 disc undercounted it); a neighbour's core joins when two 0.30 inks meet; the rune keeps its own glow at
    /// the full peak under its core; the neighbours' layers go under and over it (motion.py's page_scan). Over 1426.0 to
    /// 1426.5 s at 1/30 every pixel keeps the page floors: textDim 4.523 on 360 x 740, 4.604 on 402 x 874. The negative is
    /// the page glow motion.py's derivation refused: at 0.145 the same frames fail textDim (4.400).
    func testThePageRunesKeepTheFloorsUnderTheirNeighboursGlow() {
        func c(_ n: String) -> UIColor { Tokens.uiColor(n).resolvedColor(with: traits(.norse)) }
        func phi(_ x: Double) -> Double { erfc(-x / 2.0.squareRoot()) / 2 }
        let bg = c("bg")
        let grounds = [bg] + NorseGlow.specs.map { s -> UIColor in
            let (px, py) = (min(max(s.centreX, 0), 1), min(max(s.centreY, 0), 1))
            let d = hypot((s.centreX - px) / s.radiusX, (s.centreY - py) / s.radiusY)
            return over(UIColor(hex: s.hex), CGFloat(s.alpha * max(0, 1 - d / s.clearAt) * Strength.of("norsePageGlow")), bg)
        }
        let texts = [("text", c("text")), ("textDim", c("textDim")), ("accent", c("accent"))]
        let strength = Strength.of("norsePageRunes"), ink = GlyphGlowGoldens.inkPage, peak = GlyphGlowGoldens.page
        func worst(_ size: CGSize, glowTop: Double) -> [String: Double] {
            var out = Dictionary(uniqueKeysWithValues: texts.map { ($0.0, 99.0) })
            for n in 42780...42795 {   // 1426.0 ... 1426.5 s at 1/30
                let t = Double(n) / 30
                let tail = { () -> Double in   // the reach: the least z (0.01 steps) with glowTop Phi(-z) under one 8-bit step
                    var z = 0.0
                    while glowTop * phi(-z) >= 1.0 / 255 { z = ((z + 0.01) * 100).rounded() / 100 }
                    return ink + z * RuneGlow.blur
                }()
                let poses = (0..<RuneDrift.count).map { i -> (RuneDrift.Pose, Double, Double) in
                    let p = RuneDrift.pose(i, t, in: size)
                    let a = RuneGlow.alphas(opacity: p.opacity, strength: strength, top: RuneGlow.pageFlare,
                                            flare: RuneGlow.flare(i, t), glowTop: glowTop, still: false)
                    return (p, a.core, a.glow)
                }
                for (a, (pa, ca, ha)) in poses.enumerated() where ca > 0 {
                    var layers: [(UIColor, Double)] = []
                    for (b, (pb, cb, hb)) in poses.enumerated() where b != a {
                        let d = hypot(pa.x - pb.x, pa.y - pb.y)
                        let meet = d < 0.30 * (pa.size + pb.size)
                        let near = max(0, d - ink * pa.size)
                        if near >= tail * pb.size && !meet { continue }
                        let colb = UIColor(hex: pb.silver ? ArtDraw.runeSilverHex : ArtDraw.runeGoldHex)
                        layers.append((colb, hb * min(peak, phi(-(near - ink * pb.size) / (RuneGlow.blur * pb.size)))))
                        if meet { layers.append((colb, cb)) }
                    }
                    if layers.isEmpty { continue }
                    let cola = UIColor(hex: pa.silver ? ArtDraw.runeSilverHex : ArtDraw.runeGoldHex)
                    for g in grounds {
                        var under = g, top = over(cola, CGFloat(ca), over(cola, CGFloat(ha * peak), g))
                        for (col, al) in layers {
                            under = over(col, CGFloat(al), under)
                            top = over(col, CGFloat(al), top)
                        }
                        for px in [over(cola, CGFloat(ca), over(cola, CGFloat(ha * peak), under)), top] {
                            for (name, fg) in texts { out[name] = min(out[name] ?? 99, contrast8(fg, px)) }
                        }
                    }
                }
            }
            return out
        }
        for (size, want) in [(CGSize(width: 360, height: 740), ["text": 8.883, "textDim": 4.523, "accent": 5.288]),
                             (CGSize(width: 402, height: 874), ["text": 9.043, "textDim": 4.604, "accent": 5.382])] {
            let got = worst(size, glowTop: RuneGlow.pageGlow)
            XCTAssertGreaterThanOrEqual(got["text"] ?? 0, 7, "text at \(size)")
            XCTAssertGreaterThanOrEqual(got["textDim"] ?? 0, 4.5, "textDim at \(size)")
            XCTAssertGreaterThanOrEqual(got["accent"] ?? 0, 4.5, "accent at \(size)")
            for (name, value) in want {
                XCTAssertEqual(got[name] ?? 0, value, accuracy: 0.002, "\(name) at \(size): motion.py 1e's worst")
            }
        }
        let refused = worst(CGSize(width: 360, height: 740), glowTop: 0.145)
        XCTAssertLessThan(refused["textDim"] ?? 9, 4.5, "D-246's 0.145 fails these frames")
        XCTAssertEqual(refused["textDim"] ?? 9, 4.400, accuracy: 0.002)
    }

    /// D-214 (F2, the founder's 8-bit rule): the Aurora pages' floors with the three curtains STACKED as the renderer
    /// draws them: the engine's poses every second over 0..441 s (both swings of every curtain), the transform about each
    /// curtain's centre (scale, then skew), the full colour stops interpolated premultiplied, the streaks at their mean
    /// (a blur wider than the pattern leaves it), the night or the dawn (its ellipse included), each curtain composited
    /// source-over in order (what both shells draw onto the ground), 8-bit pixels. Grid: y 0.07..0.59 by 0.02 (below the
    /// status bar, through the bright band), x 0..1 by 0.05, on a 402 x 874 page. An exact port of .drafts/d214/stack.py,
    /// its worst values pinned to that script's source-over printout (independent goldens, BUILD-LOG D-214). The arched
    /// hem is left out, as stack.py leaves it out: it only removes curtain area, and every curtain colour is lighter than
    /// the night ground and darker than the dawn, so a curtain only moves the ground toward the text and the hem can
    /// only raise a ratio (stack.py rerun with the hem: the same worst values).
    func testTheStackedCurtainsKeepTheAuroraPageFloors() {
        typealias RGB = (r: Double, g: Double, b: Double)
        func rgb(_ c: UIColor) -> RGB {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            c.getRed(&r, green: &g, blue: &b, alpha: &a)
            return (Double(r), Double(g), Double(b))
        }
        func lum8(_ c: RGB) -> Double {
            func ch(_ x: Double) -> Double {
                let v = (x * 255).rounded() / 255
                return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b)
        }
        func over(_ fg: RGB, _ a: Double, _ g: RGB) -> RGB {
            (g.r + (fg.r - g.r) * a, g.g + (fg.g - g.g) * a, g.b + (fg.b - g.b) * a)
        }
        func lerp(_ stops: [(Double, RGB)], _ f: Double) -> RGB {
            for (a, b) in zip(stops, stops.dropFirst()) where f >= a.0 && f <= b.0 {
                return over(b.1, (f - a.0) / (b.0 - a.0), a.1)
            }
            return stops.last!.1
        }
        func hex(_ h: String) -> RGB { rgb(UIColor(hex: h)) }
        let night: [(Double, RGB)] = [(0, hex("#03070D")), (0.55, hex("#071321")), (1, hex("#0A1B26"))]
        let dawn: [(Double, RGB)] = [(0, hex("#E1F1EC")), (0.55, hex("#EEF5F3")), (1, hex("#EEF5F3"))]
        let glow = hex("#D2F0E5")
        func ground(_ isNight: Bool, _ x: Double, _ y: Double) -> RGB {
            if isNight { return lerp(night, y) }
            let base = lerp(dawn, y)
            // The dawn's ellipse: centred (0.5, -0.10), radii (1.20, 0.60), the glow at its centre to clear at 0.70.
            let d = (pow((x - 0.5) / 1.20, 2) + pow((y + 0.10) / 0.60, 2)).squareRoot() / 0.70
            return d < 1 ? over(glow, max(0, 1 - d), base) : base
        }
        /// Colour and alpha at f down a curtain, interpolated premultiplied (as Core Graphics and Skia do).
        func stop(_ stops: [Curtains.Stop], _ f: Double) -> (RGB, Double) {
            for (s0, s1) in zip(stops, stops.dropFirst()) where f >= s0.at && f <= s1.at {
                let u = (f - s0.at) / (s1.at - s0.at)
                let a = s0.alpha + (s1.alpha - s0.alpha) * u
                if a <= 0 { return ((0, 0, 0), 0) }
                func pm(_ x0: Double, _ x1: Double) -> Double { (x0 / 255 * s0.alpha + (x1 / 255 * s1.alpha - x0 / 255 * s0.alpha) * u) / a }
                return ((pm(s0.r, s1.r), pm(s0.g, s1.g), pm(s0.b, s1.b)), a)
            }
            return ((0, 0, 0), 0)
        }
        let (w, h) = (402.0, 874.0)
        let xs = (0...20).map { Double($0) * 0.05 }
        let ys = (0..<27).map { 0.07 + Double($0) * 0.02 }
        XCTAssertEqual(Curtains.streakMean, 0.60, accuracy: 1e-12, "the Look Book's streak pattern averages 0.60")
        func walk(_ set: ThemeSet) -> [String: Double] {
            let isNight = set.isDark
            let k = Strength.of(isNight ? "auroraDarkPageCurtains" : "auroraLightPageCurtains")
            let names = ["text", "textDim", "groundFaint", "groundAccent"]
            let tokenLum = names.map { lum8(rgb(Tokens.uiColor($0).resolvedColor(with: traits(set)))) }
            let grounds = ys.map { y in xs.map { x in ground(isNight, x, y) } }
            var worst = names.map { _ in Double.infinity }
            for ti in 0..<442 {
                let curtains = Curtains.specs.indices.map { i -> (cx: Double, cy: Double, tanSkew: Double, sx: Double, sy: Double, cw: Double, ch: Double, a: Double) in
                    let p = Curtains.pose(i, Double(ti))
                    let (cw, ch) = (p.width * w, p.height * h)
                    let opacity = isNight ? Curtains.specs[i].nightOpacity : Curtains.specs[i].lightOpacity
                    return (p.x * w + cw / 2, p.y * h + ch / 2, tan(p.skewDegrees * .pi / 180), p.sx, p.sy, cw, ch,
                            Curtains.streakMean * opacity * k)
                }
                for (yi, y) in ys.enumerated() {
                    for (xi, x) in xs.enumerated() {
                        var col = grounds[yi][xi]
                        for (i, c) in curtains.enumerated() {
                            // Back through the curtain's transform about its centre: the scale, then the skew.
                            let (u, v) = (x * w - c.cx, y * h - c.cy)
                            let ly = v / c.sy
                            let lx = (u - c.tanSkew * c.sx * ly) / c.sx
                            guard abs(lx) <= c.cw / 2, abs(ly) <= c.ch / 2 else { continue }
                            let fy = (ly + c.ch / 2) / c.ch
                            let (colour, alpha) = stop(Curtains.specs[i].stops, fy)
                            if alpha > 0 { col = over(colour, alpha * c.a, col) }
                        }
                        let l = lum8(col)
                        for (n, tl) in tokenLum.enumerated() {
                            worst[n] = min(worst[n], (max(l, tl) + 0.05) / (min(l, tl) + 0.05))
                        }
                    }
                }
            }
            return Dictionary(uniqueKeysWithValues: zip(names, worst))
        }
        // stack.py's source-over printout (.drafts/d214/stack-test.out), 8-bit: the port's goldens.
        let goldens: [ThemeSet: [String: Double]] = [
            .auroraDark: ["text": 11.68, "textDim": 5.24, "groundFaint": 5.24, "groundAccent": 8.25],
            .auroraLight: ["text": 12.62, "textDim": 4.71, "groundFaint": 4.71, "groundAccent": 4.71],
        ]
        let floors = ["text": 7.0, "textDim": 4.5, "groundFaint": 4.5, "groundAccent": 4.5]
        for set in [ThemeSet.auroraDark, .auroraLight] {
            let worst = walk(set)
            for (name, floor) in floors {
                XCTAssertEqual(worst[name]!, goldens[set]![name]!, accuracy: 0.01, "\(set) \(name): stack.py's worst")
                XCTAssertGreaterThanOrEqual(worst[name]!, floor, "\(set) \(name) on the stacked curtains is \(worst[name]!)")
            }
        }
    }

    /// D-179's tiles, per set: every letter clears 4.5:1 on its fill.
    func testEveryGroupToneLetterIsReadableOnItsFillInEverySet() {
        for set in ThemeSet.allCases {
            for tone in GroupTone.allCases {
                let fill = UIColor(hex: Tokens.hex(tone.column.fill, set))
                let letter = UIColor(hex: Tokens.hex(tone.column.letter, set))
                XCTAssertGreaterThanOrEqual(contrast(letter, fill), 4.5, "\(set) \(tone)")
            }
        }
    }

    /// D-220 (L19): Vardr Labs' outlined pill keeps each tone's words at 4.5 on every ground it can sit on (bg, surface,
    /// surfaceRaised; the hero tone on the hero's stops), goldens from art.py section 13; the selected chip's border (accent
    /// at 1) holds 3. An outlined pill on the page ground fills with bg, so it is measured on bg, never on a grid dot.
    func testOutlinedPillWordsClearTheirGrounds() throws {
        func c(_ name: String, _ set: ThemeSet) -> UIColor { Tokens.uiColor(name).resolvedColor(with: traits(set)) }
        // (bg, surface, surfaceRaised) per tone, light then dark: art.py section 13.
        let golden: [ThemeSet: [PillTone: [Double]]] = [
            .vardrLight: [.accent: [5.42, 5.68, 5.53], .owner: [6.65, 6.96, 6.78], .selected: [5.42, 5.68, 5.53], .unselected: [13.25, 13.89, 13.53]],
            .vardrDark: [.accent: [8.60, 8.29, 7.26], .owner: [9.56, 9.20, 8.07], .selected: [8.60, 8.29, 7.26], .unselected: [16.47, 15.87, 13.91]],
        ]
        let hero: [ThemeSet: Double] = [.vardrLight: 13.89, .vardrDark: 16.47]
        for set in [ThemeSet.vardrLight, .vardrDark] {
            for onPage in [false, true] {
                for (tone, want) in golden[set]! {
                    let paint = PillPaint.of(tone, look: .outlined, onPage: onPage)
                    let words = UIColor(hex: paint.words.hex(set))
                    for (i, ground) in ["bg", "surface", "surfaceRaised"].enumerated() {
                        let r = contrast(words, c(ground, set))
                        XCTAssertGreaterThanOrEqual(r, 4.5, "\(set) \(tone) words on \(ground)")
                        XCTAssertEqual(r, want[i], accuracy: 0.01, "\(set) \(tone) on \(ground): art.py's value")
                    }
                    if tone == .selected {
                        let border = try XCTUnwrap(paint.border, "\(set): the selected pill has its border")
                        for ground in ["bg", "surface", "surfaceRaised"] {
                            XCTAssertGreaterThanOrEqual(contrast(UIColor(hex: border.ink.hex(set)), c(ground, set)), 3, "\(set) selected border on \(ground)")
                        }
                    }
                }
            }
            let words = UIColor(hex: PillPaint.of(.hero, look: .outlined).words.hex(set))
            for stop in ["heroStart", "heroMid", "heroEnd"] {
                let r = contrast(words, c(stop, set))
                XCTAssertGreaterThanOrEqual(r, 4.5, "\(set) hero pill words on \(stop)")
                XCTAssertEqual(r, hero[set]!, accuracy: 0.01, "\(set) hero pill words on \(stop): art.py's value")
            }
        }
    }

    /// D-221 (E4): the ring, the meter, the avatars, the seal, the progress dot and the celebration's words clear their
    /// floors in every set; each ratio is art.py's own (sections 18 to 23, two decimals).
    func testE4PairsHoldTheirFloors() {
        func c(_ name: String, _ set: ThemeSet) -> UIColor { Tokens.uiColor(name).resolvedColor(with: traits(set)) }
        // Per set, in ThemeSet order: auroraLight, auroraDark, norse, vardrLight, vardrDark.
        let pairs: [(String, String, Double, [Double])] = [
            ("ring", "ringTrack", 3, [3.26, 8.98, 7.08, 3.59, 8.46]),
            ("meterStart", "ringTrack", 3, [3.26, 5.85, 5.53, 3.99, 6.78]),
            ("accent", "accentSoft", 4.5, [4.53, 8.93, 7.81, 5.07, 7.86]),
            ("onAccent", "accent", 4.5, [5.30, 11.87, 9.60, 5.68, 8.60]),
        ]
        for (fg, ground, floor, golden) in pairs {
            for (k, set) in ThemeSet.allCases.enumerated() {
                let r = contrast(c(fg, set), c(ground, set))
                XCTAssertGreaterThanOrEqual(r, floor, "\(set) \(fg) on \(ground) is \(r)")
                XCTAssertEqual(r, golden[k], accuracy: 0.005, "\(set) \(fg) on \(ground): art.py's value")
            }
        }
        // Vardr Labs' progress dot (ring) on the pill's page fill (bg), 3:1 non-text.
        for (set, golden) in [(ThemeSet.vardrLight, 4.88), (.vardrDark, 10.75)] {
            let r = contrast(c("ring", set), c("bg", set))
            XCTAssertGreaterThanOrEqual(r, 3, "\(set) progress dot")
            XCTAssertEqual(r, golden, accuracy: 0.005, "\(set) progress dot: art.py's value")
        }
        // The celebration's words over the page dimmed to 0.22: the worst pixel is text at 0.22 over bg. Text clears
        // 4.5 there; textDim does not in four sets, which is why the subtitle is drawn in text (art.py 23). D-242: Norse's
        // Vibrant textDim clears it (4.84, art.py 23's "ok"); the subtitle stays in text on every set.
        for (k, set) in ThemeSet.allCases.enumerated() {
            let worst = over(c("text", set), 0.22, c("bg", set))
            let r = contrast(c("text", set), worst)
            XCTAssertGreaterThanOrEqual(r, 4.5, "\(set) celebration words")
            XCTAssertEqual(r, [10.03, 9.72, 9.50, 8.58, 8.95][k], accuracy: 0.005, "\(set) celebration words: art.py's value")
            let dim = contrast(c("textDim", set), worst)
            XCTAssertEqual(dim, [3.75, 4.36, 4.84, 4.20, 3.88][k], accuracy: 0.005, "\(set) textDim there: art.py's value")
            if set != .norse { XCTAssertLessThan(dim, 4.5, "\(set) textDim would fail there") }
        }
    }

    /// D-213: every pair the static art adds clears its floor (.drafts/d213/art.py prints the same table).
    func testTheArtsPairsClearTheirFloors() {
        func c(_ name: String, _ set: ThemeSet) -> UIColor { Tokens.uiColor(name).resolvedColor(with: traits(set)) }
        // D-219 (L15): the decided grounds. Aurora's is glass: the surface at 0.72 (iOS's film) or 0.94 (Android's) over
        // the page's bg; Norse and Vardr Labs' are the solid surface token.
        func bars(_ set: ThemeSet) -> [UIColor] {
            set.theme == .aurora ? [0.72, 0.94].map { over(c("surface", set), $0, c("bg", set)) } : [c("surface", set)]
        }
        // Norse cards: the gradient takes the surface's place; every text floor on both stops.
        let floors: [(String, Double)] = [("text", 7), ("textDim", 4.5), ("textFaint", 4.5), ("owed", 4.5),
                                          ("owing", 4.5), ("neutral", 4.5), ("watch", 4.5), ("accent", 4.5)]
        for stop in ["norseCardTop", "norseCardBottom"] {
            for (fg, floor) in floors {
                let r = contrast(c(fg, .norse), ThemeArt.uiColor(stop))
                XCTAssertGreaterThanOrEqual(r, floor, "norse \(fg) on \(stop) is \(r)")
            }
        }
        // D-219 (L13, L15): the labels on every set's bar ground; Aurora's filled accent pill on its glass bar and the
        // active glyph (onAccent) on the pill.
        for set in ThemeSet.allCases {
            for bar in bars(set) {
                XCTAssertGreaterThanOrEqual(contrast(c("text", set), bar), 4.5, "\(set) text on the bar")
                XCTAssertGreaterThanOrEqual(contrast(c("textDim", set), bar), 4.5, "\(set) textDim on the bar")
            }
        }
        for set in [ThemeSet.auroraLight, .auroraDark] {
            for bar in bars(set) {
                XCTAssertGreaterThanOrEqual(contrast(c("accent", set), bar), 3, "\(set) pill on the bar")
            }
            XCTAssertGreaterThanOrEqual(contrast(c("onAccent", set), c("accent", set)), 3, "\(set) glyph on the pill")
        }
        // Norse: the gold rune and underline on the tab bar.
        for bar in bars(.norse) { XCTAssertGreaterThanOrEqual(contrast(c("accent", .norse), bar), 3) }
        // Vardr Labs: the outline (ring at its alpha) and the glyph (ring) on the bar; the outlined button;
        // the frame marks; text over the grid's darkest dot.
        for set in [ThemeSet.vardrLight, .vardrDark] {
            for bar in bars(set) {
                let outline = over(c("ring", set), CGFloat(TabIndicator.outlineAlpha(set)), bar)
                XCTAssertGreaterThanOrEqual(contrast(outline, bar), 3, "\(set) outline")
                XCTAssertGreaterThanOrEqual(contrast(c("ring", set), bar), 3, "\(set) glyph")
            }
            for ground in ["bg", "surface"] {
                XCTAssertGreaterThanOrEqual(contrast(c("accent", set), c(ground, set)), 4.5, "\(set) outlined words on \(ground)")
            }
            XCTAssertGreaterThanOrEqual(contrast(c("owed", set), c("bg", set)), 4.5, "\(set) destructive words")
            let mark = over(c("textDim", set), CGFloat(ThemeArt.m("frameAlpha")), c("surface", set))
            XCTAssertGreaterThanOrEqual(contrast(mark, c("surface", set)), 3, "\(set) frame marks")
            let dot = over(c("accent", set), CGFloat(ThemeArt.m("gridAlpha")), c("bg", set))
            XCTAssertGreaterThanOrEqual(contrast(c("text", set), dot), 7, "\(set) text on a dot")
            XCTAssertGreaterThanOrEqual(contrast(c("textDim", set), dot), 4.5, "\(set) textDim on a dot")
            // D-220 A2 (WORKLOG decision 7): accent text straight on the page ground holds 4.5 on the grid's darkest dot,
            // on 8-bit pixels (palette.py section A2: Vardr Labs light's accent 4.12 -> groundAccent #475B6B 4.52).
            XCTAssertGreaterThanOrEqual(contrast8(c("groundAccent", set), dot), 4.5, "\(set) groundAccent on a dot")
            for ground in ["bg", "surface", "surfaceRaised"] {
                XCTAssertGreaterThanOrEqual(contrast8(c("groundAccent", set), c(ground, set)), 4.5, "\(set) groundAccent on \(ground)")
            }
        }
        XCTAssertEqual(hex(c("groundAccent", .vardrLight)), "#475B6B", "A2: the darker ground accent")
        XCTAssertEqual(contrast8(c("groundAccent", .vardrLight), over(c("accent", .vardrLight), CGFloat(ThemeArt.m("gridAlpha")), c("bg", .vardrLight))), 4.52, accuracy: 0.01)
        XCTAssertEqual(hex(c("groundAccent", .vardrDark)), "#9DB0BF", "dark's accent already held 4.5 on its dot")
        // The + glyph on the art under it: every colour inside the 28 pt glyph box (art.py section 7).
        let diamond = [ThemeArt.uiColor("diamondTop"), ThemeArt.uiColor("diamondBottom")]
        let span = ThemeArt.m("diamondSide") * sqrt(2)
        let lo = 0.5 - 14 / span, hi = 0.5 + 14 / span
        XCTAssertEqual(lo, 0.3453, accuracy: 0.0001, "D-219: the diamond is 64 a side now")
        XCTAssertEqual(hi, 0.6547, accuracy: 0.0001)
        for t in [lo, hi] {
            let under = over(diamond[1], t, diamond[0])
            XCTAssertGreaterThanOrEqual(contrast(c("onAccent", .norse), under), 3, "diamond at \(t)")
        }
        // The bolt's gradient runs from its start to its end unit point; the glyph box's extent along it is
        // t = 0.5 -/+ half / L (art.py: L = 91.43 pt, half = 21.86 pt for the 32 pt reticle in 72 x 64 at 150 degrees). Both ends
        // and every stop between them.
        let (w, h) = (Double(ThemeArt.m("boltWidth")), Double(ThemeArt.m("boltHeight")))
        let gx = Double(ThemeArt.m("boltEndX") - ThemeArt.m("boltStartX")) * w
        let gy = Double(ThemeArt.m("boltEndY") - ThemeArt.m("boltStartY")) * h
        let length = (gx * gx + gy * gy).squareRoot()
        let half = Double(ThemeArt.m("reticleGlyph")) / 2 * (abs(gx) + abs(gy)) / length
        XCTAssertEqual(length, 91.43, accuracy: 0.05)
        XCTAssertEqual(half, 21.86, accuracy: 0.05)
        let (blo, bhi) = (0.5 - half / length, 0.5 + half / length)
        var worst = Double.infinity
        for (set, dark) in [(ThemeSet.vardrDark, true), (.vardrLight, false)] {
            let stops = ThemeArt.boltStops(dark: dark).map { (Double($0.location), UIColor($0.color)) }
            func at(_ t: Double) -> UIColor {
                for (a, b) in zip(stops, stops.dropFirst()) where t >= a.0 && t <= b.0 {
                    return over(b.1, CGFloat((t - a.0) / (b.0 - a.0)), a.1)
                }
                return stops.last?.1 ?? .clear
            }
            let under = [at(blo), at(bhi)] + stops.filter { $0.0 > blo && $0.0 < bhi }.map(\.1)
            for color in under {
                let r = contrast(c("onAccent", set), color)
                worst = min(worst, r)
                XCTAssertGreaterThanOrEqual(r, 3, "\(set) bolt \(hex(color))")
            }
        }
        XCTAssertEqual(worst, 3.85, accuracy: 0.01, "art.py section 7 at 32: the worst (Vardr Labs light, the glyph box's top end)")
    }

    /// D-222 (L63): the drawn slider's step under a touch, the thumb's centre and the adjustable action, goldens from
    /// .drafts/d222/art.py section 29 (width 300, thumb 20): half up on both shells (floor(v + 0.5)), clamped.
    func testTheStepSliderGoldens() {
        let at: [(CGFloat, Int)] = [(-20, -2), (0, -2), (10, -2), (37.9, -2), (38, -1), (66, -1), (150, 1), (290, 3), (300, 3), (400, 3)]
        for (x, step) in at { XCTAssertEqual(StepSlider.step(atX: x, width: 300, thumb: 20), step, "x \(x)") }
        let centres: [(Int, CGFloat)] = [(-2, 10), (-1, 66), (0, 122), (1, 178), (2, 234), (3, 290)]
        for (step, x) in centres { XCTAssertEqual(StepSlider.centre(step: step, width: 300, thumb: 20), x, accuracy: 1e-9, "step \(step)") }
        for (step, x) in centres { XCTAssertEqual(StepSlider.step(atX: x, width: 300, thumb: 20), step, "a thumb's centre picks its step") }
        let adjusted: [(Int, Int, Int)] = [(-2, -1, -2), (-2, 1, -1), (0, 1, 1), (3, 1, 3), (3, -1, 2)]
        for (step, by, result) in adjusted { XCTAssertEqual(StepSlider.adjusted(step, by: by), result, "\(step) by \(by)") }
    }

    /// D-222 (L62): Norse shows Dark chosen and the control disabled whatever is stored (the stored choice is untouched);
    /// Aurora and Vardr Labs show the stored mode, enabled.
    func testTheModeChoiceShown() {
        for stored in AppearanceMode.allCases {
            XCTAssertEqual(ModeChoice.shown(theme: .norse, stored: stored), ModeChoice(chosen: .dark, enabled: false), "\(stored)")
            for theme in [AppTheme.aurora, .vardr] {
                XCTAssertEqual(ModeChoice.shown(theme: theme, stored: stored), ModeChoice(chosen: stored, enabled: true), "\(theme) \(stored)")
            }
        }
    }

    /// D-234 (A1, the founder's): the sample line is "Lunch at Shake Shack" and 48 US dollars for everyone, whatever the
    /// default currency; minor units and the string from core (`$48.00` in both locales core knows: format.rs).
    func testTheSampleLine() throws {
        XCTAssertEqual(SampleLine.parts(amount: "$48.00"), ["Lunch at Shake Shack", "$48.00"])
        XCTAssertEqual(SampleLine.amount(locale: .enUs), "$48.00")
        XCTAssertEqual(SampleLine.amount(locale: .enIn), "$48.00", "the same line for everyone")
        XCTAssertEqual(SampleLine.currency, "USD")
        // Source guard: the amount comes from core, in USD, and nothing in the sample formats a number in Swift.
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/AccountHubScreen.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        let body = try XCTUnwrap(source.components(separatedBy: "enum SampleLine {").dropFirst().first?.components(separatedBy: "\n}\n").first)
        XCTAssertFalse(body.contains("DefaultCurrency"), "never the person's default currency")
        XCTAssertTrue(body.contains("ffiMoneyParseMinor(digits: wholeAmount, currencyCode: currency)"), "minor units from core")
        XCTAssertTrue(body.contains("ffiMoneyFormat("), "the string from core")
        for banned in ["NumberFormatter", "String(format:", "\"%", ".formatted("] {
            XCTAssertFalse(body.contains(banned), banned)
        }
    }

    func testStoredValuesThisBuildDoesNotKnowReadAsTheDefaults() {
        XCTAssertEqual(AppTheme.parse(nil), .aurora)
        XCTAssertEqual(AppTheme.parse("night"), .aurora)
        XCTAssertEqual(AppTheme.parse("norse"), .norse)
        XCTAssertEqual(AppTheme.allCases.map(\.label), ["Aurora", "Norse", "Vardr Labs"])
        XCTAssertEqual(AppearanceMode.parse(nil), .dark, "D-232: a new install opens in Dark")
        XCTAssertEqual(AppearanceMode.parse("sepia"), .dark, "D-232: an unknown word reads as the default, Dark")
        XCTAssertEqual(AppearanceMode.parse("night"), .dark, "Night is retired for now: it reads as Dark")
        XCTAssertEqual(AppearanceMode.allCases.map(\.label), ["System", "Light", "Dark"])
    }

    // MARK: - D-232: Aurora dark is the default for a new install

    /// Decisions 73 and 75: nothing stored, or a word this build does not know, reads as Dark; the three words read as
    /// themselves; a stored `night` (before D-212) still reads as Dark. Android's twin: a_new_install_reads_dark.
    func testANewInstallReadsDark() {
        XCTAssertEqual(AppearanceMode.parse(nil), .dark, "nothing stored")
        XCTAssertEqual(AppearanceMode.parse("bogus"), .dark, "a word this build does not know")
        XCTAssertEqual(AppearanceMode.parse(""), .dark, "an empty word")
        XCTAssertEqual(AppearanceMode.parse("System"), .dark, "case matters: the words are stored lower case")
        XCTAssertEqual(AppearanceMode.parse("night"), .dark, "Night is retired: it reads as Dark")
        XCTAssertEqual(AppearanceMode.parse("system"), .system)
        XCTAssertEqual(AppearanceMode.parse("light"), .light)
        XCTAssertEqual(AppearanceMode.parse("dark"), .dark)
    }

    /// Decision 74: the launch stores `system` only where nothing is stored and the vault file exists (the install has
    /// run before and followed the phone); a brand-new install (no vault yet) stores nothing and so reads Dark; a stored
    /// word, known or not, is never touched. The table is the twin of Android's a_launch_keeps_an_existing_install_on_system.
    func testTheLaunchMigrationKeepsAnExistingInstallOnSystem() {
        let table: [(stored: String?, vault: Bool, want: String?)] = [
            (nil, true, "system"), (nil, false, nil),
            ("light", true, nil), ("light", false, nil),
            ("dark", true, nil), ("system", true, nil),
            ("night", true, nil), ("bogus", true, nil),
        ]
        for row in table {
            XCTAssertEqual(AppearanceMode.migrate(stored: row.stored, vaultExists: row.vault), row.want,
                           "\(row.stored ?? "nil"), vault \(row.vault)")
        }
        XCTAssertEqual(AppearanceMode.settledKey, "appearance.modeSettled")
        XCTAssertEqual(AppearanceMode.storageKey, "appearance.mode", "the key every earlier build wrote")
    }

    // MARK: - D-232 Task 2: the launch settles the default once, before the mode is first read

    /// The launch's wiring over a throwaway suite and a throwaway vault file. A new install stores nothing (reads Dark)
    /// and stays that way at its second launch, when its vault exists; an install that ran before and never chose
    /// stores `system`; a stored choice is kept. Every first launch leaves the settled mark.
    func testTheLaunchSettlesTheDefaultOnce() throws {
        let name = "d232-settle-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let vault = dir.appendingPathComponent("ledger-vault.db").path

        // A brand-new install: no vault yet.
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertNil(defaults.string(forKey: AppearanceMode.storageKey), "a new install stores nothing")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.settledKey), "the first launch leaves the mark")
        XCTAssertFalse(defaults.bool(forKey: AppearanceMode.migratedKey), "D-244 M3: nothing migrated")
        XCTAssertEqual(AppearanceState.stored(defaults, lowRam: false).mode, .dark, "and opens in Dark")
        // Its second launch: the first one made the vault.
        XCTAssertTrue(FileManager.default.createFile(atPath: vault, contents: Data([0])))
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertNil(defaults.string(forKey: AppearanceMode.storageKey), "the second launch of a new install is still Dark")

        // An install that ran before D-232 and never chose: its vault exists, nothing is settled.
        defaults.removeObject(forKey: AppearanceMode.settledKey)
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "system", "an existing install keeps following the phone")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.settledKey))
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.migratedKey), "D-244 M3: the stored system is marked migrated")
        XCTAssertEqual(AppearanceState.stored(defaults, lowRam: false).mode, .system)

        // An install that chose Light before D-232: kept.
        defaults.set("light", forKey: AppearanceMode.storageKey)
        defaults.removeObject(forKey: AppearanceMode.settledKey)
        defaults.removeObject(forKey: AppearanceMode.migratedKey)
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "light")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.settledKey))
        XCTAssertFalse(defaults.bool(forKey: AppearanceMode.migratedKey), "a chosen mode is never marked migrated")
    }

    /// The migration checks the file the launch opens: the path `LedgerStore.defaultStore()` gives and `loadGate` builds.
    func testTheMigrationChecksTheLaunchsVaultFile() throws {
        XCTAssertEqual(AppearanceDefault.defaultVaultPath(), LedgerStore.defaultStore().vaultPath)
        let gate = try between(try appFile("SkiptiApp.swift"), from: "private func loadGate(retry: Bool = false) {",
                               to: "let launch = VaultRecovery.launch(store)")
        XCTAssertTrue(gate.contains(".appendingPathComponent(\"ledger\", isDirectory: true)"), gate)
        XCTAssertTrue(gate.contains("dir.appendingPathComponent(\"ledger-vault.db\").path"), gate)
    }

    /// Order matters (decision 74): settling the default is the first thing the app does, before Firebase, the
    /// emulator sign-in (which can open, and so create, the vault) and any read of the mode; it is called nowhere else;
    /// and every `@AppStorage` reader of the mode is `String?` (a non-optional default would read "system" and hide the
    /// dark default from `AppearanceMode.parse`).
    func testTheDefaultIsSettledFirstAtLaunch() throws {
        let app = try appFile("SkiptiApp.swift")
        let initBody = try between(app, from: "    init() {\n", to: "\n    }\n")
        let first = initBody.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty && !$0.hasPrefix("//") }
        XCTAssertEqual(first, "AppearanceDefault.settle()", "the first statement of SkiptiApp.init")
        // D-232 final wave (M4): Swift runs SkiptiApp's stored-property initialisers before init's body, so none of them
        // may reach the vault: those above init, and those between init and `var body`.
        let above = try between(app, from: "struct SkiptiApp: App {\n", to: "    init() {\n")
        let below = try between(app, from: "    init() {\n", to: "    var body: some Scene {\n")
            .components(separatedBy: "\n    }\n").dropFirst().joined(separator: "\n    }\n")
        XCTAssertFalse(below.isEmpty, "the stored properties after init")
        let initialisers = (above + "\n" + below).split(separator: "\n").map(String.init)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") && $0.contains(" = ") }
            .map { $0.components(separatedBy: " = ").dropFirst().joined(separator: " = ") }
        XCTAssertGreaterThanOrEqual(initialisers.count, 5, "the scan found SkiptiApp's stored-property initialisers")
        for value in initialisers {
            for vault in ["LedgerStore", "vault", "loadGate", "Sync."] {
                XCTAssertFalse(value.contains(vault), "a stored property of SkiptiApp reaches the vault before settle: \(value)")
            }
        }
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let optional = try NSRegularExpression(pattern: #"@AppStorage\(AppearanceMode\.storageKey\) private var \w+: String\?\n"#)
        var calls = 0, readers = 0, optionalReaders = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path) where file.hasSuffix(".swift") {
            let text = try String(contentsOf: dir.appendingPathComponent(file), encoding: .utf8)
            calls += occurrences(text, "AppearanceDefault.settle(")
            readers += occurrences(text, "@AppStorage(AppearanceMode.storageKey)")
            optionalReaders += optional.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
        }
        XCTAssertEqual(calls, 1, "settled once, from SkiptiApp.init")
        XCTAssertEqual(readers, 3, "the applier, the You tab and the Appearance page")
        XCTAssertEqual(optionalReaders, readers, "every reader of the mode is String?")
    }

    /// Norse is always dark; the person's own choice is kept for later.
    @MainActor
    func testNorseForcesDarkAndKeepsTheStoredMode() {
        XCTAssertEqual(AppearanceMode.light.interfaceStyle(theme: .norse), .dark)
        XCTAssertEqual(AppearanceMode.system.interfaceStyle(theme: .norse), .dark)
        XCTAssertEqual(AppearanceMode.light.interfaceStyle(theme: .aurora), .light)
        let state = AppearanceState()
        state.mode = .light
        state.theme = .norse
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 10, height: 10))
        Appearance.apply(state, to: window)
        XCTAssertEqual(window.traitCollection.userInterfaceStyle, .dark)
        XCTAssertEqual(window.traitCollection[ThemeTrait.self], .norse)
        state.theme = .aurora
        Appearance.apply(state, to: window)
        XCTAssertEqual(window.traitCollection.userInterfaceStyle, .light, "back on Aurora the stored Light returns")
        XCTAssertEqual(state.mode, .light)
    }

    /// D-172: System must leave the phone's style alone.
    @MainActor
    func testSystemRemovesTheStyleOverrideInsteadOfSettingOne() {
        let state = AppearanceState()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 10, height: 10))
        state.mode = .dark
        Appearance.apply(state, to: window)
        XCTAssertTrue(window.traitOverrides.contains(UITraitUserInterfaceStyle.self))
        state.mode = .system
        Appearance.apply(state, to: window)
        XCTAssertFalse(window.traitOverrides.contains(UITraitUserInterfaceStyle.self),
                       "System must not override the phone's light or dark setting")
    }

    func testTextSizeStepsMoveThePhonesSizeAndClampAtBothEnds() {
        XCTAssertEqual(TextSize.parse(nil), 0)
        XCTAssertEqual(TextSize.parse(4), 0)
        XCTAssertEqual(TextSize.parse(-3), 0)
        XCTAssertEqual(TextSize.parse(-2), -2)
        XCTAssertEqual(TextSize.parse(3), 3)
        XCTAssertEqual(TextSize.category(phone: .large, step: 0), .large)
        XCTAssertEqual(TextSize.category(phone: .large, step: -2), .small)
        XCTAssertEqual(TextSize.category(phone: .large, step: 3), .extraExtraExtraLarge)
        XCTAssertEqual(TextSize.category(phone: .extraSmall, step: -2), .extraSmall)
        XCTAssertEqual(TextSize.category(phone: .accessibilityExtraExtraExtraLarge, step: 3), .accessibilityExtraExtraExtraLarge)
        XCTAssertEqual(TextSize.category(phone: .unspecified, step: 1), .extraLarge)
        XCTAssertEqual(TextSize.accessibilityValue(0), "Your phone's size")
        XCTAssertEqual(TextSize.accessibilityValue(-1), "1 step smaller")
        XCTAssertEqual(TextSize.accessibilityValue(3), "3 steps larger")
    }

    /// Step 0 leaves the phone's size alone; any other step overrides it.
    @MainActor
    func testTextSizeIsAWindowOverrideOnlyAwayFromZero() {
        let state = AppearanceState()
        state.phoneCategory = .large
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 10, height: 10))
        state.textStep = 2
        Appearance.apply(state, to: window)
        XCTAssertEqual(window.traitCollection.preferredContentSizeCategory, .extraExtraLarge)
        state.textStep = 0
        Appearance.apply(state, to: window)
        XCTAssertFalse(window.traitOverrides.contains(UITraitPreferredContentSizeCategory.self))
    }

    /// D-174: the weight a role asks for is the weight Space Grotesk draws.
    func testSpaceGroteskDrawsTheWeightARoleAsksFor() throws {
        for weight: CGFloat in [400, 600, 700] {
            let font = TypeRole.uiFont(TypeRole.grotesk, size: 17, weight: weight, tabular: true)
            let variation = try XCTUnwrap(CTFontCopyVariation(font as CTFont) as? [NSNumber: NSNumber])
            XCTAssertEqual(variation[TypeRole.weightAxis]?.doubleValue ?? -1, Double(weight), accuracy: 0.5)
        }
    }

    /// 16 Sep 2026, 10:24 UTC: the minute (24) can never pass for the month.
    func testDatesNeverShowTheMinuteAsTheMonth() {
        let ms: UInt64 = 1_789_554_240_000
        let day = AppDate.day(ms, locale: .enUs)
        XCTAssertTrue(day.contains("Sep") && day.contains("2026"), day)
        XCTAssertFalse(day.contains(":") || day.contains(" at "), "a day has no time: \(day)")
        XCTAssertEqual(ImportReviewModel.dateString(ms, locale: .enIn), AppDate.day(ms, locale: .enIn))
        XCTAssertTrue(AppDate.stamp(ms, locale: .enUs).contains(":"), "a stamp carries the time")
    }

    /// D-225 (A4, A10): the expense detail's "Added by" stamp is one wording on both phones, Android's
    /// `LedgerStoreTest.the_added_by_stamp_is_one_wording_with_ios` pins the same strings: the patterns Foundation's
    /// `dMMMyjmm` resolved to for en_US and en_IN (read off it once, 2026-10-17), a narrow no-break space before the
    /// marker, "Sep" on both tags, midnight as 12; and with the phone set to 24-hour, `HH:mm`. Written out, so no ICU
    /// update moves them; the 12 or 24-hour choice is the phone's.
    func testTheAddedByStampIsOneWordingOnBothPhones() throws {
        let ms: UInt64 = 1_792_216_860_000 // 2026-10-17T06:01:00Z
        let september: UInt64 = 1_789_516_800_000 // 2026-09-16T00:00:00Z
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let kolkata = try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
        let n = "\u{202F}"
        let goldens: [(UInt64, LocaleTag, TimeZone, Bool, String)] = [
            (ms, .enUs, utc, false, "Oct 17, 2026 at 6:01\(n)AM"),
            (ms, .enIn, utc, false, "17 Oct 2026 at 6:01\(n)AM"),
            (ms, .enUs, kolkata, false, "Oct 17, 2026 at 11:31\(n)AM"),
            (ms, .enIn, kolkata, false, "17 Oct 2026 at 11:31\(n)AM"),
            (ms + 8 * 3_600_000, .enUs, utc, false, "Oct 17, 2026 at 2:01\(n)PM"),
            (september, .enIn, utc, false, "16 Sep 2026 at 12:00\(n)AM"),
            (september, .enUs, kolkata, false, "Sep 16, 2026 at 5:30\(n)AM"),
            (ms, .enUs, utc, true, "Oct 17, 2026 at 06:01"),
            (ms, .enIn, utc, true, "17 Oct 2026 at 06:01"),
            (ms, .enUs, kolkata, true, "Oct 17, 2026 at 11:31"),
            (ms, .enIn, kolkata, true, "17 Oct 2026 at 11:31"),
            (ms + 8 * 3_600_000, .enUs, utc, true, "Oct 17, 2026 at 14:01"),
            (september, .enIn, utc, true, "16 Sep 2026 at 00:00"),
            (september, .enUs, kolkata, true, "Sep 16, 2026 at 05:30"),
        ]
        for (at, tag, zone, hours24, want) in goldens {
            XCTAssertEqual(AppDate.stamp(at, locale: tag, timeZone: zone, hours24: hours24), want, "\(tag) \(zone.identifier) 24h \(hours24)")
        }
        // The phone's own setting decides: its hour pattern carries a day-period marker or not (a quoted word does not).
        for (pattern, hours24) in [("h a", false), ("a h", false), ("h B", false), ("HH", true), ("H", true), ("HH 'at'", true)] {
            XCTAssertEqual(AppDate.uses24Hour(hourPattern: pattern), hours24, pattern)
        }
        // The detail screen's stamp is this one.
        XCTAssertEqual(ExpenseDetailView.stamp(ms, locale: .enIn), AppDate.stamp(ms, locale: .enIn))
        // D-225 fix wave (the controller's stamp decision): the calendar is pinned Gregorian, after the locale (setting
        // a locale resets a formatter's calendar), so the year and month never follow another calendar; Android's
        // formatter pins it too.
        let stamp = try between(try appFile("AppLocale.swift"), from: "    static func stamp(", to: "\n    }\n")
        let locale = try XCTUnwrap(stamp.range(of: "formatter.locale = Locale(identifier: \"en_US_POSIX\")"))
        let calendar = try XCTUnwrap(stamp.range(of: "formatter.calendar = Calendar(identifier: .gregorian)"), "Gregorian, explicitly")
        XCTAssertLessThan(locale.lowerBound, calendar.lowerBound, "after the locale")
    }

    // ---- D-222 Task 2: Appearance drawn (source guards over AccountHubScreen.swift) ----

    private func appFile(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text between the first `from` and the next `to` after it.
    private func between(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    private func occurrences(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

    /// D-222 (L59, Q9 a, replacing D-212's preview test: the decision it pinned is gone): each swatch draws its OWN
    /// theme's dark art, still, whatever the mode or the phone: Aurora the night sky with the hero's curtains and stars;
    /// Norse its ground, a 1 pt inner border and ᚠᚷᛗᛏ in its gold; Vardr Labs its dark ground, the dot grid and the frame
    /// marks. Colours by token name in the fixed `.norse` / `.vardrDark` sets; clipped to the current theme's panel; the
    /// card shaped by the current theme's control.
    func testSwatchesAreEachThemesDarkArt() throws {
        let hub = try appFile("AccountHubScreen.swift")
        let swatch = try between(hub, from: "struct ThemeSwatch: View {", to: "\n}\n")
        let aurora = try between(swatch, from: "case .aurora:", to: "case .norse:")
        let norse = try between(swatch, from: "case .norse:", to: "case .vardr:")
        let vardr = try between(swatch, from: "case .vardr:", to: ".frame(height: ThemeArt.m(\"swatchHeight\"))")
        for needle in ["NightSky()", "Canvas { ctx, size in", "ArtDraw.curtains(&ctx, size, Curtains.still, night: true,",
                       "curtainStrength: Strength.of(\"auroraHeroCurtains\")", "starStrength: Strength.of(\"auroraHeroStars\")"] {
            XCTAssertTrue(aurora.contains(needle), "Aurora swatch: \(needle)")
        }
        for needle in ["Self.color(\"bg\", .norse)", "clip.stroke(Self.color(\"accent\", .norse), lineWidth: 2)",
                       "Text(verbatim: Runes.balance + Runes.expenses + Runes.groups + Runes.settle)",
                       ".font(TypeRole.rune(ThemeArt.m(\"swatchRune\")))",
                       ".tracking(ThemeArt.m(\"swatchRune\") * ThemeArt.m(\"swatchRuneTracking\"))",
                       ".foregroundStyle(Self.color(\"accent\", .norse))"] {
            XCTAssertTrue(norse.contains(needle), "Norse swatch: \(needle)")
        }
        for needle in ["Self.color(\"bg\", .vardrDark)", "ArtGeometry.gridDots(size, pitch: ThemeArt.m(\"swatchGridPitch\"))",
                       "ThemeArt.m(\"swatchGridDot\")", ".color(Self.color(\"ring\", .vardrDark))",
                       "FrameMarks(inset: ThemeArt.m(\"swatchMarkInset\"), length: ThemeArt.m(\"swatchMarkLength\"))",
                       ".stroke(Self.color(\"accent\", .vardrDark), lineWidth: 1)"] {
            XCTAssertTrue(vardr.contains(needle), "Vardr Labs swatch: \(needle)")
        }
        for needle in ["Tokens.hex(Tokens.palette[name]!, set)",
                       "let clip = ThemeArt.panelShape(AppearanceState.shared.theme, radius: ThemeArt.m(\"swatchRadius\"))",
                       ".clipShape(clip)", ".accessibilityHidden(true)"] {
            XCTAssertTrue(swatch.contains(needle), "ThemeSwatch: \(needle)")
        }
        for banned in ["scheme", "colorScheme", ".auroraLight", ".auroraDark", ".vardrLight", "ThemeSet.resolve(", "ArtCanvas("] {
            XCTAssertFalse(swatch.contains(banned), "ThemeSwatch reads \(banned)")
        }
        let card = try between(hub, from: "struct ThemeCard: View {", to: "\n}\n")
        for needle in ["ThemeArt.controlShape(AppearanceState.shared.theme)", "ThemeSwatch(theme: theme)",
                       "VStack(spacing: ThemeArt.m(\"themeGap\"))", ".padding(ThemeArt.m(\"themePad\"))",
                       "selected ? Tokens.accent : Tokens.hairline", ".contentShape(shape)"] {
            XCTAssertTrue(card.contains(needle), "ThemeCard: \(needle)")
        }
        for banned in ["previewDark", "phoneIsDark", "ArtCanvas(", "let dark"] {
            XCTAssertFalse(hub.contains(banned), "AccountHubScreen.swift: \(banned)")
        }
        // The swatch's colours are the fixed sets' own, whatever the phone's mode.
        XCTAssertEqual(ThemeSwatch.hex("bg", .norse), Tokens.hex(Tokens.palette["bg"]!, .norse))
        XCTAssertEqual(ThemeSwatch.hex("ring", .vardrDark), Tokens.hex(Tokens.palette["ring"]!, .vardrDark))
        XCTAssertNotEqual(ThemeSwatch.hex("bg", .vardrDark), Tokens.hex(Tokens.palette["bg"]!, .vardrLight))
    }

    /// D-222 (L59): the theme row and the mode control sit on the ground (no card); only Text size is a card, holding
    /// the slider, the sample and the caption in that order; each label is a section label above its content. No system
    /// control is drawn: the one segmented Picker and the one Slider are the drawn controls' accessibility representations.
    func testAppearanceSitsOnTheGround() throws {
        let hub = try appFile("AccountHubScreen.swift")
        let screen = try between(hub, from: "struct SettingsSectionScreen: View {", to: "\n}\n")
        let theme = try between(screen, from: "case .theme:", to: "case .appearanceMode:")
        let mode = try between(screen, from: "case .appearanceMode:", to: "case .textSize:")
        // D-225: Security's and Your data's items share one case (`case .appLock, .privacy, ...`) since their pages draw them.
        let size = try between(screen, from: "case .textSize:", to: "case .appLock, .privacy")
        for (name, part) in [("theme", theme), ("mode", mode)] {
            XCTAssertFalse(part.contains("card {"), "\(name) in a card")
            XCTAssertFalse(part.contains(".glassCard("), "\(name) in a card")
        }
        XCTAssertTrue(theme.contains("Text(verbatim: AppearanceCopy.cards[0]).sectionLabel()"))
        XCTAssertTrue(theme.contains("ThemeCard(theme: theme, selected: AppTheme.parse(themeRaw) == theme)"))
        XCTAssertTrue(mode.contains("Text(verbatim: AppearanceCopy.cards[1]).sectionLabel()"))
        XCTAssertTrue(mode.contains("ModeControl(theme: theme, stored: Binding("))
        XCTAssertTrue(size.contains("Text(verbatim: AppearanceCopy.cards[2]).sectionLabel()"))
        XCTAssertEqual(occurrences(size, "card {"), 1, "Text size is one card")
        // D-244 (ADR-0043), amended on purpose: the slider writes through the one write of a synced setting.
        let order = ["Text(verbatim: AppearanceCopy.cards[2])", "card {",
                     "TextSizeSlider(step: Binding(get: { textStep }, set: { SettingsSync.set(TextSize.storageKey, $0) }))",
                     "Separator.text(SampleLine.parts(amount: SampleLine.amount()), size: TypeScale.sample)",
                     "AppearanceCopy.textSizeCaption"]
        let at = try order.map { try XCTUnwrap(size.range(of: $0), $0).lowerBound }
        XCTAssertEqual(at, at.sorted(), "label, card, slider, sample, caption")
        XCTAssertTrue(size.contains(".font(TypeRole.name(TypeScale.sample)).foregroundStyle(Tokens.text)\n                            .padding(.top, 8)"))
        XCTAssertTrue(size.contains("AppearanceCopy.textSizeCaption).font(TypeRole.body(AppearanceCopy.detailSize)).foregroundStyle(Tokens.textDim)\n                            .padding(.top, 6)"))
        for (part, label) in [(theme, "theme"), (mode, "mode"), (size, "size")] {
            XCTAssertTrue(part.contains("VStack(alignment: .leading, spacing: 8) {"), "\(label): its label 8 above its content")
        }
        XCTAssertFalse(screen.contains("@Environment(\\.colorScheme)"), "nothing on the page reads the phone's style")
        // The only segmented picker and the only Slider are the representations.
        XCTAssertEqual(occurrences(hub, ".pickerStyle(.segmented)"), 1)
        let control = try between(hub, from: "struct ModeControl: View {", to: "\n}\n")
        XCTAssertEqual(occurrences(try between(control, from: ".accessibilityRepresentation {", to: "\n        }\n"), ".pickerStyle(.segmented)"), 1)
        let slider = try NSRegularExpression(pattern: #"(?<![A-Za-z])Slider\("#)
        XCTAssertEqual(slider.numberOfMatches(in: hub, range: NSRange(hub.startIndex..., in: hub)), 1)
        let drawn = try between(hub, from: "struct TextSizeSlider: View {", to: "\n}\n")
        let rep = try between(drawn, from: ".accessibilityRepresentation {", to: "\n        }\n")
        XCTAssertEqual(slider.numberOfMatches(in: rep, range: NSRange(rep.startIndex..., in: rep)), 1)
    }

    /// D-222 (L62, L66): the mode control is drawn: a 48 pt touch row holding the 40 pt `accentSoft` track in the theme's
    /// pill shape, the chosen segment inset 4 in `surface` with its shadow, words through the name role at
    /// `TypeScale.segment` (chosen `text`, the others `textDim`) on one line shrinking to 0.7; Norse shows Dark chosen,
    /// disabled at `ButtonLook.disabledAlpha`. VoiceOver reads a real segmented Picker (`.accessibilityRepresentation`).
    /// Under it, ONLY in Norse, "Norse is always dark."; the per-mode detail lines are gone.
    func testTheModeControlIsDrawnAndAccessible() throws {
        let hub = try appFile("AccountHubScreen.swift")
        let control = try between(hub, from: "struct ModeControl: View {", to: "\n}\n")
        for needle in [
            "let shown = ModeChoice.shown(theme: theme, stored: stored)", "let shape = ThemeArt.pillShape(theme)",
            ".frame(maxWidth: .infinity, minHeight: ThemeArt.m(\"touchTarget\"))", "ThemeArt.m(\"modeTrack\")",
            "ThemeArt.m(\"modeInset\")", ".font(TypeRole.name(TypeScale.segment))",
            ".foregroundStyle(chosen ? Tokens.text : Tokens.textDim)", ".lineLimit(1)", ".minimumScaleFactor(0.7)",
            "shape.fill(Tokens.surface)", "shape.fill(Tokens.accentSoft)", "ThemeArt.m(\"segmentShadowAlpha\")",
            "ThemeArt.m(\"segmentShadowRadius\")", "ThemeArt.m(\"segmentShadowY\")",
            ".opacity(shown.enabled ? 1 : ButtonLook.disabledAlpha)", ".disabled(!shown.enabled)",
        ] {
            XCTAssertTrue(control.contains(needle), "ModeControl: \(needle)")
        }
        let rep = try between(control, from: ".accessibilityRepresentation {", to: "\n        }\n")
        for needle in ["Picker(AppearanceCopy.cards[1], selection:", ".pickerStyle(.segmented)", ".disabled(!shown.enabled)",
                       "Text(mode.label).tag(mode)"] {
            XCTAssertTrue(rep.contains(needle), "the representation: \(needle)")
        }
        let screen = try between(hub, from: "struct SettingsSectionScreen: View {", to: "\n}\n")
        let mode = try between(screen, from: "case .appearanceMode:", to: "case .textSize:")
        XCTAssertTrue(mode.contains("""
            if theme.forcesDark {
                                    Text(verbatim: AppearanceCopy.norseAlwaysDark)
            """), mode)
        XCTAssertTrue(mode.contains("VStack(alignment: .leading, spacing: 6) {\n                    ModeControl("), "the Norse line 6 below")
        XCTAssertFalse(mode.contains("else"), "a line outside Norse")
        XCTAssertNil(mode.range(of: #"\.detail\b"#, options: .regularExpression), "a per-mode detail line")
        let appearance = try appFile("Appearance.swift")
        XCTAssertFalse(try between(appearance, from: "enum AppearanceMode:", to: "\n}\n").contains("var detail"))
        for gone in ["Follows your phone's light and dark setting.", "Always light.", "Always dark."] {
            XCTAssertFalse(appearance.contains(gone) || hub.contains(gone), gone)
        }
    }

    /// D-222 (L63, L65): the text-size slider is drawn: a 48 pt touch row, the `sliderTrack` track in `ringTrack` and its
    /// filled part in `accent` in the theme's meter shape, the `sliderThumb` thumb in the person tile's shape ringed
    /// `sliderRing` in `accent`; a tap or drag picks `StepSlider.step(atX:)` and writes only a new step. VoiceOver reads a
    /// real Slider (the representation) whose swipes move one step (`.accessibilityAdjustableAction`). The two A's are
    /// fixed, 14 at 400 and 23 at 700, both in `text`.
    func testTheSliderIsDrawnAndAdjustable() throws {
        let hub = try appFile("AccountHubScreen.swift")
        // Fix wave (X3): the track, fill and thumb are drawn by `SliderBar`, which the slider holds at its measured width.
        let slider = try between(hub, from: "struct TextSizeSlider: View {", to: "\n}\n")
            + (try between(hub, from: "struct SliderBar: View {", to: "\n}\n"))
        XCTAssertTrue(slider.contains("SliderBar(step: current, width: width)"))
        for needle in [
            "StepSlider.step(atX: value.location.x, width: width, thumb: thumb)", "StepSlider.centre(step: step, width: width, thumb: thumb)",
            "ThemeArt.meterShape(theme)", "TileShape.of(theme, person: true)", ".frame(width: thumb, height: thumb)", "track.fill(Tokens.ringTrack)", "track.fill(Tokens.accent)",
            "ThemeArt.m(\"sliderThumb\")", "ThemeArt.m(\"sliderRing\")", "ThemeArt.m(\"sliderTrack\")",
            ".frame(height: ThemeArt.m(\"touchTarget\"))", "DragGesture(minimumDistance: 0)", "if next != current { step = next }",
            "Text(verbatim: \"A\").font(TypeRole.bodyFixed(TypeScale.rangeSmall)).foregroundStyle(Tokens.text).accessibilityHidden(true)",
            "Text(verbatim: \"A\").font(TypeRole.bodyFixed(TypeScale.rangeLarge, weight: 700)).foregroundStyle(Tokens.text).accessibilityHidden(true)",
            "case .increment: step = StepSlider.adjusted(current, by: 1)", "case .decrement: step = StepSlider.adjusted(current, by: -1)",
        ] {
            XCTAssertTrue(slider.contains(needle), "TextSizeSlider: \(needle)")
        }
        let rep = try between(slider, from: ".accessibilityRepresentation {", to: "\n        }\n")
        for needle in ["Slider(", "in: Double(StepSlider.lo)...Double(StepSlider.hi), step: 1",
                       ".accessibilityLabel(Text(verbatim: AppearanceCopy.cards[2]))",
                       ".accessibilityValue(Text(verbatim: TextSize.accessibilityValue(current)))",
                       ".accessibilityAdjustableAction(adjust)"] {
            XCTAssertTrue(rep.contains(needle), "the representation: \(needle)")
        }
        XCTAssertFalse(slider.contains("Tokens.textDim"), "the A's are in the text colour")
    }

    // ---- D-222 final fix wave: the drawn controls, measured (X1 to X4) ----

    /// [view] rendered at [scale] with [theme] in the shared state and the `appTheme` trait, read back one pixel at a
    /// time in points (RGB, 0 to 255). The host's applier can write the shared theme between a set and a render (the
    /// ThemeArtTests.render pattern): set, render, and render again only when the theme did not hold, up to 3 tries.
    @MainActor
    private func measured(_ theme: AppTheme, scheme: ColorScheme, size: CGSize, scale: CGFloat = 3,
                          _ view: some View) throws -> (CGFloat, CGFloat) -> [Int] {
        let state = AppearanceState.shared
        for _ in 0..<3 {
            state.theme = theme
            let renderer = ImageRenderer(content: view.environment(\.appTheme, theme).environment(\.colorScheme, scheme)
                .frame(width: size.width, height: size.height))
            renderer.scale = scale
            let image = try XCTUnwrap(renderer.cgImage)
            let (w, h) = (image.width, image.height)
            var bytes = [UInt8](repeating: 0, count: w * h * 4)
            let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
                guard let context = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                              space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
                context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
                return true
            }
            XCTAssertTrue(drawn)
            guard state.theme == theme else { continue }
            let copy = bytes
            return { x, y in
                let px = Int((x * scale).rounded(.down)), py = Int((y * scale).rounded(.down))
                guard px >= 0, py >= 0, px < w, py < h else { return [-1, -1, -1] }
                let i = (py * w + px) * 4
                return (0..<3).map { Int(copy[i + $0]) }
            }
        }
        throw NSError(domain: "AppearanceTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "the host reset the theme three times"])
    }

    private func rgb(_ name: String, _ set: ThemeSet) -> [Int] {
        let hex = Tokens.hex(Tokens.palette[name]!, set)
        let v = Int(hex.dropFirst(), radix: 16)!
        return [(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF]
    }

    /// [top] at [alpha] over [ground], per channel, rounded.
    private func over(_ ground: [Int], _ top: [Int], _ alpha: Double) -> [Int] {
        zip(ground, top).map { Int((Double($0) * (1 - alpha) + Double($1) * alpha).rounded()) }
    }

    private func contrast(_ a: [Int], _ b: [Int]) -> Double {
        func lum(_ c: [Int]) -> Double {
            let l = c.map { v -> Double in
                let s = Double(v) / 255
                return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]
        }
        let (x, y) = (lum(a), lum(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    private func near(_ a: [Int], _ b: [Int], _ tolerance: Int) -> Bool { zip(a, b).allSatisfy { abs($0 - $1) <= tolerance } }

    /// The brightest pixel (by channel sum) in a box, in points.
    private func brightest(_ px: (CGFloat, CGFloat) -> [Int], x: ClosedRange<CGFloat>, y: ClosedRange<CGFloat>) -> [Int] {
        var best = [0, 0, 0]
        for i in stride(from: x.lowerBound, through: x.upperBound, by: 1.0 / 3) {
            for j in stride(from: y.lowerBound, through: y.upperBound, by: 1.0 / 3) {
                let p = px(i, j)
                if p.reduce(0, +) > best.reduce(0, +) { best = p }
            }
        }
        return best
    }

    /// X1 (final review I-1): the segments sit `modeInset` inside the track's ends and the chosen pill fills its whole
    /// segment, `(W - 2 inset) / 3`, as Android and the Look Book draw it (the pill was inset again inside its third, 5 pt
    /// narrower). Aurora light, System chosen, 362 pt: the pill runs from 4 to 122 on the row's middle line.
    @MainActor
    func testTheModeControlsChosenPillFillsItsSegment() throws {
        let state = AppearanceState.shared
        let before = state.theme
        defer { state.theme = before }
        let width: CGFloat = 362
        let px = try measured(.aurora, scheme: .light, size: CGSize(width: width, height: 48),
                              ZStack { Tokens.bg; ModeControl(theme: .aurora, stored: .constant(.system)) })
        let surface = rgb("surface", .auroraLight)
        let inset = ThemeArt.m("modeInset")
        let right = inset + (width - 2 * inset) / 3
        XCTAssertFalse(near(px(inset - 1, 24), surface, 3), "the track shows before the pill: \(px(inset - 1, 24))")
        XCTAssertTrue(near(px(inset + 1, 24), surface, 3), "the pill starts at the inset: \(px(inset + 1, 24))")
        XCTAssertTrue(near(px(right - 1, 24), surface, 3), "the pill reaches its segment's end: \(px(right - 1, 24))")
        XCTAssertFalse(near(px(right + 1, 24), surface, 3), "and stops there: \(px(right + 1, 24))")
        let hub = try appFile("AccountHubScreen.swift")
        let control = try between(hub, from: "struct ModeControl: View {", to: "\n}\n")
        let chosen = try between(control, from: "if chosen {", to: "\n                            }\n")
        XCTAssertFalse(chosen.contains(".padding(.horizontal"), "the pill is not inset inside its segment")
        XCTAssertTrue(control.contains(".padding(.horizontal, inset)\n                        .padding(.vertical, margin + inset)"), "the words 4 inside")
        XCTAssertTrue(control.contains("}\n        .padding(.horizontal, inset)\n        .background { shape.fill(Tokens.accentSoft)"),
                      "the segments inset, the track the whole width")
    }

    /// X2 (the D-222 look, F1): Norse's disabled control is dimmed ONCE, as a group, as Android's `alpha` dims it: the
    /// chosen pill is `surface` at 0.45 over the page and the track `accentSoft` at 0.45 (contrast 1.062 on D-242's
    /// palette, 1.047 before, Android's measure then), and the words are `text` and `textDim` at 0.45 (the chosen 3.83:1
    /// on its pill on D-242's palette, Android's 3.68 before; .drafts/d242/modecontrol.py derives both), not dimmed a
    /// second time by the disabled buttons. Before: the pill at 0.45 over the track at 0.45 (1.00) and the words 1.84:1.
    @MainActor
    func testNorsesModeControlIsDimmedOnceAsAGroup() throws {
        let state = AppearanceState.shared
        let before = state.theme
        defer { state.theme = before }
        let width: CGFloat = 362
        let px = try measured(.norse, scheme: .dark, size: CGSize(width: width, height: 48),
                              ZStack { Tokens.bg; ModeControl(theme: .norse, stored: .constant(.light)) })
        let a = ButtonLook.disabledAlpha
        let bg = rgb("bg", .norse)
        let pill = over(bg, rgb("surface", .norse), a)
        let track = over(bg, rgb("accentSoft", .norse), a)
        let third = (width - 8) / 3
        // Dark is chosen: the third segment, from 4 + 2 thirds; its word is centred, so 12 pt in is clear pill.
        let pillAt = px(4 + 2 * third + 12, 24), trackAt = px(4 + 12, 24)
        XCTAssertTrue(near(pillAt, pill, 2), "the chosen pill \(pillAt), expected \(pill)")
        XCTAssertTrue(near(trackAt, track, 2), "the track \(trackAt), expected \(track)")
        XCTAssertEqual(contrast(pillAt, trackAt), 1.062, accuracy: 0.012, "pill on track, modecontrol.py's 1.062")
        let darkWord = brightest(px, x: (4 + 2 * third + 25)...(4 + 3 * third - 25), y: 16...32)
        XCTAssertTrue(near(darkWord, over(bg, rgb("text", .norse), a), 8), "the chosen word \(darkWord)")
        XCTAssertEqual(contrast(darkWord, pillAt), 3.83, accuracy: 0.2, "the chosen word on its pill, modecontrol.py's 3.83")
        let systemWord = brightest(px, x: (4 + 25)...(4 + third - 25), y: 16...32)
        XCTAssertTrue(near(systemWord, over(bg, rgb("textDim", .norse), a), 8), "another word \(systemWord)")
    }

    /// D-234 (L13, the D-232 look): a disabled primary button is dimmed ONCE, as a group, as Android's `alpha` dims it:
    /// its fill is the accent at 0.45 over the ground and its label onAccent at 0.45 over the same ground (Android's
    /// near-black), never the label dimmed a second time over the dimmed fill (iOS's dim green before). Aurora dark.
    @MainActor
    func testADisabledPrimaryButtonIsDimmedOnceAsAGroup() throws {
        let state = AppearanceState.shared
        let before = state.theme
        defer { state.theme = before }
        let px = try measured(.aurora, scheme: .dark, size: CGSize(width: 300, height: 64),
                              ZStack { Tokens.bg; Button("Add expense") {}.buttonStyle(PrimaryButtonStyle()).disabled(true).padding(6) })
        let a = ButtonLook.disabledAlpha
        let bg = rgb("bg", .auroraDark)
        let fill = px(16, 32)
        XCTAssertTrue(near(fill, over(bg, rgb("accent", .auroraDark), a), 3), "the fill \(fill)")
        var darkest = [255, 255, 255]
        for x in stride(from: CGFloat(100), through: 200, by: 1.0 / 3) {
            for y in stride(from: CGFloat(22), through: 42, by: 1.0 / 3) {
                let p = px(x, y)
                if p.reduce(0, +) < darkest.reduce(0, +) { darkest = p }
            }
        }
        XCTAssertTrue(near(darkest, over(bg, rgb("onAccent", .auroraDark), a), 10), "the label \(darkest)")
        // Source: every two-layer button body groups before it dims (the one-layer outlined secondary needs none).
        let tokens = try appFile("DesignTokens.swift")
        XCTAssertEqual(occurrences(tokens, ".compositingGroup()\n                .opacity(isEnabled ? 1 : ButtonLook.disabledAlpha)"), 3)
    }

    /// X3 (the D-222 look, F4): the slider's track runs between the thumb's two end centres, so at either end the track's
    /// end and the fill's start lie under the thumb: at the smallest step no gold beside Norse's diamond's left tip, at the
    /// largest no track past its right tip (both checked 2 pt above the centre line, inside the 5 pt track, where the
    /// diamond is 2 narrower than its tip). The thumb itself is one geometry: the person tile's shape filled `surface`, its
    /// ring a `sliderRing` stroke centred on the shape inset by half the ring: Norse's diamond 17.88 tip to tip (the tile
    /// radius's quad corner takes 3 / (2 sqrt 2) = 1.06 off each tip; D-226: tileRadius 3, 18.59 at 2 before;
    /// .drafts/d226/art.py section 46) and its ring 2.5 through the tip.
    @MainActor
    func testTheSlidersEndsHideUnderItsThumb() throws {
        let state = AppearanceState.shared
        let before = state.theme
        defer { state.theme = before }
        let width: CGFloat = 300
        let bg = rgb("bg", .norse)
        func drawn(_ step: Int) throws -> (CGFloat, CGFloat) -> [Int] {
            try measured(.norse, scheme: .dark, size: CGSize(width: width, height: 48), scale: 4,
                         ZStack(alignment: .leading) { Tokens.bg; SliderBar(step: step, width: width) })
        }
        let low = try drawn(StepSlider.lo)
        // The ring's outer edge crosses these rows at 1.48 from each end: the pixels from 0.25 to 0.5 and 0.75 to 1.0.
        for x: CGFloat in [0.25, 0.75] {
            for y: CGFloat in [22, 26] {
                XCTAssertTrue(near(low(x, y), bg, 2), "smallest step, beside the left tip at (\(x), \(y)): \(low(x, y))")
            }
        }
        let high = try drawn(StepSlider.hi)
        for x: CGFloat in [width - 1, width - 0.5] {
            for y: CGFloat in [22, 26] {
                XCTAssertTrue(near(high(x, y), bg, 2), "largest step, past the right tip at (\(x), \(y)): \(high(x, y))")
            }
        }
        // The thumb at step 0, down its centre column (the track crosses only 21.5 to 26.5): ink from tip to tip, and the
        // accent ring from the top tip to the surface.
        let mid = try drawn(0)
        let c = StepSlider.centre(step: 0, width: width, thumb: ThemeArt.m("sliderThumb"))
        let accent = rgb("accent", .norse), surface = rgb("surface", .norse)
        var top: CGFloat?, bottom: CGFloat?, ring: CGFloat = 0
        for y in stride(from: CGFloat(10), through: 38, by: 0.25) {
            let p = mid(c, y)
            // A tip's edge pixel counts once it is at least half ink (nearer the ring's accent than the page).
            if contrast(p, accent) < contrast(p, bg) { top = top ?? y; bottom = y }
            if y < 21.5, contrast(p, accent) < contrast(p, surface), contrast(p, accent) < contrast(p, bg) { ring += 0.25 }
        }
        let span = try XCTUnwrap(bottom) - XCTUnwrap(top) + 0.25
        XCTAssertEqual(span, 17.88, accuracy: 0.35, "the diamond tip to tip")
        XCTAssertEqual(ring, 2.5, accuracy: 0.35, "the ring through the tip")
        let hub = try appFile("AccountHubScreen.swift")
        let bar = try between(hub, from: "struct SliderBar: View {", to: "\n}\n")
        XCTAssertTrue(bar.contains("knob.stroke(Tokens.accent, lineWidth: ring).padding(ring / 2)"), "the ring, centred on the inset shape")
    }

    /// X4 (final review S5): the sample line is read as its parts joined by a comma, like Android's contentDescription:
    /// Norse's ᛫ separator is never read aloud.
    func testTheSampleLineIsReadWithoutItsSeparator() throws {
        XCTAssertEqual(SampleLine.spoken(amount: "$48.00"), "Lunch at Shake Shack, $48.00")
        let hub = try appFile("AccountHubScreen.swift")
        let screen = try between(hub, from: "struct SettingsSectionScreen: View {", to: "\n}\n")
        // D-225: Security's and Your data's items share one case (`case .appLock, .privacy, ...`) since their pages draw them.
        let size = try between(screen, from: "case .textSize:", to: "case .appLock, .privacy")
        XCTAssertTrue(size.contains(".accessibilityLabel(Text(verbatim: SampleLine.spoken(amount: SampleLine.amount())))"), size)
    }

    // ---- D-225 Task 2: the list card, the list row, the switch row and the choice page (ListRows.swift) ----

    /// [text] with every `.accessibilityRepresentation { ... }` block cut out (braces matched), so what is left is what
    /// the screen draws.
    private func withoutRepresentations(_ text: String) -> String {
        var out = ""
        var rest = Substring(text)
        while let start = rest.range(of: ".accessibilityRepresentation {") {
            out += rest[..<start.lowerBound]
            var depth = 1
            var i = start.upperBound
            while i < rest.endIndex, depth > 0 {
                if rest[i] == "{" { depth += 1 } else if rest[i] == "}" { depth -= 1 }
                i = rest.index(after: i)
            }
            rest = rest[i...]
        }
        return out + rest
    }

    /// D-225 (Design 1, L67, L73, L74, L-D217-1): one row for every settings list: the optional tile, a `rowGap`, the
    /// title through the name role at 16 (in `owed` under the danger tone) over the summary parts joined by the theme's
    /// separator at `TypeScale.detail` in `textDim`, `rowLineGap` under the title, then the trailing piece; padded
    /// `rowPadV`, never shorter than `touchTarget`, one spoken element. The card is S1's: `glassCard(padding: 0)` with the
    /// rows padded `listPadH` / `listPadV`, a `hairline` between two rows. S1's `SettingsRow` and You's list call them.
    func testTheListRowIsOneComponent() throws {
        let rows = try appFile("ListRows.swift")
        let row = try between(rows, from: "struct ListRow: View {", to: "\n}\n")
        for needle in [
            "if let glyph { SettingsTile(glyph: glyph) }", "HStack(spacing: ThemeArt.m(\"rowGap\"))",
            "VStack(alignment: .leading, spacing: ThemeArt.m(\"rowLineGap\"))",
            "Text(verbatim: title).font(TypeRole.name(16)).foregroundStyle(tone == .danger ? Tokens.owed : Tokens.text)",
            "Separator.text(parts, size: TypeScale.detail)", ".font(TypeRole.body(TypeScale.detail))",
            ".lineLimit(summaryLines == 0 ? nil : summaryLines)", ".padding(.vertical, ThemeArt.m(\"rowPadV\"))",
            ".frame(minHeight: ThemeArt.m(\"touchTarget\"))", "RowChevron(color: Tokens.textDim)",
            ".font(TypeRole.body(16))", "LucideIcon(glyph: .check, size: 20)", ".foregroundStyle(Tokens.accent)",
            "ThemeSwitch(isOn: isOn.wrappedValue, enabled: enabled)", ".accessibilityElement(children: .ignore)",
            "YouRows.spoken(title: title, parts: parts)",
        ] {
            XCTAssertTrue(row.contains(needle), "ListRow: \(needle)")
        }
        XCTAssertFalse(row.contains(".joined("), "a summary joined in Swift")
        XCTAssertFalse(row.contains("Spacer("), "the words take the rest of the row (D-224 R2), no spacer")
        XCTAssertEqual(occurrences(row, ".frame(maxWidth: .infinity, alignment: .leading)"), 1, "the words take the rest")
        let card = try between(rows, from: "struct ListCard<Content: View>: View {", to: "\n}\n")
            + (try between(rows, from: "private struct ListCardRows: _VariadicView_MultiViewRoot {", to: "\n}\n"))
        for needle in [".glassCard(padding: 0)", ".padding(.horizontal, ThemeArt.m(\"listPadH\"))",
                       ".padding(.vertical, ThemeArt.m(\"listPadV\"))", "Rectangle().fill(Tokens.hairline).frame(height: 1)",
                       "if row.id != children.first?.id {"] {
            XCTAssertTrue(card.contains(needle), "ListCard: \(needle)")
        }
        let hub = try appFile("AccountHubScreen.swift")
        let settingsRow = try between(hub, from: "struct SettingsRow: View {", to: "\n}\n")
        XCTAssertTrue(settingsRow.contains("ListRow(glyph: glyph, title: title, parts: parts, trailing: .chevron)"), settingsRow)
        XCTAssertFalse(settingsRow.contains("HStack("), "SettingsRow draws nothing of its own")
        let list = try between(hub, from: "                ListCard {\n                    ForEach(SettingsSection.allCases, id: \\.self) { section in",
                               to: ".padding(20)")
        XCTAssertTrue(list.contains("row(section)"), list)
        XCTAssertFalse(list.contains("Divider()"), "the card draws the hairlines")
    }

    /// The rows measured: a one-line row is `touchTarget` tall (39 raised to 48, art.py 41); a card is its rows plus
    /// `listPadV` above and below and one 1 pt hairline BETWEEN two rows (none after the last); rows from a ForEach count
    /// one by one and a false `if` adds nothing.
    @MainActor
    func testTheListCardDividesBetweenRowsOnly() throws {
        func height(_ view: some View) -> CGFloat {
            UIHostingController(rootView: view).sizeThatFits(in: CGSize(width: 362, height: CGFloat.greatestFiniteMagnitude)).height
        }
        let row = height(ListRow(title: "Name"))
        XCTAssertEqual(row, ThemeArt.m("touchTarget"), accuracy: 0.01, "a one-line row")
        XCTAssertGreaterThan(height(ListRow(title: "Name", parts: ["Goa trip"])), row, "a summary adds a line")
        let pad = 2 * ThemeArt.m("listPadV")
        XCTAssertEqual(height(ListCard { ListRow(title: "A") }), row + pad, accuracy: 0.01, "one row: no hairline")
        XCTAssertEqual(height(ListCard { ListRow(title: "A"); ListRow(title: "B") }), 2 * row + 1 + pad, accuracy: 0.01,
                       "two rows: one hairline")
        let hidden = false
        XCTAssertEqual(height(ListCard {
            ForEach(0..<3, id: \.self) { i in ListRow(title: "Row \(i)") }
            if hidden { ListRow(title: "Hidden") }
        }), 3 * row + 2 + pad, accuracy: 0.01, "three rows: two hairlines")
    }

    /// D-225 (Design 3): no system switch is drawn in the files S2 has converted so far: a `Toggle(` lives only inside
    /// an `.accessibilityRepresentation` (AppLock.swift and AddExpenseScreen.swift join in Tasks 4 and 6). Find me is a
    /// `.toggle` row; the Haptics row is one (DoneMomentsTests pins it).
    func testNoSystemToggleIsLeft() throws {
        for file in ["AccountHubScreen.swift", "People.swift", "ListRows.swift"] {
            let drawn = withoutRepresentations(try appFile(file))
            XCTAssertEqual(occurrences(drawn, "Toggle("), 0, "\(file): a system Toggle outside an accessibility representation")
        }
        // The representation's real Toggle is ThemeArtTests.testTheSwitchIsDrawnAndAccessible's to pin.
        let people = try appFile("People.swift")
        XCTAssertTrue(people.contains("ListRow(title: PeopleCopy.findableToggle, trailing: .toggle(Binding(get: { findable }, set: { on in"),
                      "Find me is a switch row")
        XCTAssertFalse(people.contains(".tint(Tokens.accent)\n            Text(verbatim: findable"), "the system switch's tint is gone")
    }

    /// D-225 (Design 10): a choice page is one list card of `.check` rows; the chosen row carries the selected trait;
    /// a pick calls `onPick` first, then returns (unless the option waits on a confirmation); the caption under the card.
    func testTheChoicePageChecksOneRow() throws {
        let page = try between(try appFile("ListRows.swift"), from: "struct ChoicePage<Option: Hashable>: View {", to: "\n}\n")
        for needle in [
            "ListCard {", "ListRow(title: option.label, parts: option.parts, trailing: .check(option.value == chosen))",
            ".accessibilityAddTraits(option.value == chosen ? .isSelected : [])", "@Environment(\\.dismiss) private var dismiss",
            "if let caption { CardCaption(caption) }", ".auroraBackground()", ".navigationTitle(Text(verbatim: title))",
            ".navigationBarTitleDisplayMode(.inline)", ".buttonStyle(.plain)",
        ] {
            XCTAssertTrue(page.contains(needle), "ChoicePage: \(needle)")
        }
        let action = try between(page, from: "Button {", to: "} label: {")
        let pick = try XCTUnwrap(action.range(of: "onPick(option.value)"), action)
        let back = try XCTUnwrap(action.range(of: "if !stays(option.value) { dismiss() }"), action)
        XCTAssertLessThan(pick.lowerBound, back.lowerBound, "the pick is written before the page returns")
        let caption = try between(try appFile("ListRows.swift"), from: "struct CardCaption: View {", to: "\n}\n")
        XCTAssertTrue(caption.contains(".font(TypeRole.body(TypeScale.detail))"), caption)
        XCTAssertTrue(caption.contains(".foregroundStyle(Tokens.textDim)"), caption)
    }

    /// D-225 T2 review (Important): a tap anywhere in a field row's field part (its `rowPadV` padding and the 48 minimum
    /// included) focuses the field, as Android's twin does; the padding and the minimum sit INSIDE the tap area, and the
    /// trailing piece (the clear cross) keeps its own taps, outside it. The tap is simultaneous, so the field's own taps
    /// (the caret, selection) still reach it.
    func testTheWholeFieldRowFocusesItsField() throws {
        let field = try between(try appFile("ListRows.swift"), from: "struct FieldRow<Trailing: View>: View {", to: "\n}\n")
        XCTAssertTrue(field.contains("@FocusState private var focused: Bool"), field)
        let body = try between(field, from: "var body: some View {", to: "\n    }\n")
        // D-248 (U29.2): the row's own focus, or its form's (`FieldFocusing`); a tap focuses whichever it has.
        let order = [".modifier(FieldFocusing(local: $focused, form: form))", ".padding(.vertical, ThemeArt.m(\"rowPadV\"))",
                     ".frame(maxWidth: .infinity, minHeight: ThemeArt.m(\"touchTarget\"), alignment: .leading)",
                     ".contentShape(Rectangle())", ".simultaneousGesture(TapGesture().onEnded {", "trailing"]
        XCTAssertTrue(body.contains("if let form { form.focus.wrappedValue = form.id } else { focused = true }"))
        let at = try order.map { try XCTUnwrap(body.range(of: $0), "FieldRow: \($0)").lowerBound }
        XCTAssertEqual(at, at.sorted(), "the field, its padding and minimum, the tap area, the focusing tap, then the trailing piece")
        XCTAssertEqual(occurrences(body, "trailing"), 1, "the trailing piece outside the tap area")
        XCTAssertFalse(body.contains(".onTapGesture"), "a plain tap would take the field's own taps")
    }

    // ---- D-216 Task 2: the app icon's decisions, names and copy (Design 3 to 5; Android's AppearanceTest pins the same
    // table, written by hand from the Design, not read from the code) ----

    /// Design 3: the stored choice. Its words are permanent from D-216; nothing stored, an empty string, a word with
    /// another case and an unknown word all read Match theme.
    func testTheIconChoiceIsStoredAsItsWord() {
        XCTAssertEqual(AppIconChoice.storageKey, "appearance.icon")
        XCTAssertEqual(AppIconChoice.allCases, [.match, .aurora, .norse, .vardr])
        XCTAssertEqual(AppIconChoice.allCases.map(\.rawValue), ["match", "aurora", "norse", "vardr"])
        let parsed: [(String?, AppIconChoice)] = [
            (nil, .match), ("", .match), ("Norse", .match), ("AURORA", .match), ("night", .match), (" norse", .match),
            ("match", .match), ("aurora", .aurora), ("norse", .norse), ("vardr", .vardr),
        ]
        for (raw, choice) in parsed { XCTAssertEqual(AppIconChoice.parse(raw), choice, "parse \(raw ?? "nil")") }
    }

    /// Design 1 and 3: three icons, one per theme, Aurora the primary (no alternate name); an alternate name the build
    /// does not know reads Aurora.
    func testTheIconsAndTheirAlternateNames() {
        XCTAssertEqual(AppIcon.allCases, [.aurora, .norse, .vardr])
        XCTAssertEqual(AppIcon.allCases.map(\.rawValue), ["aurora", "norse", "vardr"])
        XCTAssertEqual(AppIcon.of(.aurora), .aurora)
        XCTAssertEqual(AppIcon.of(.norse), .norse)
        XCTAssertEqual(AppIcon.of(.vardr), .vardr)
        XCTAssertNil(AppIcon.aurora.alternateName, "Aurora is the primary icon")
        XCTAssertEqual(AppIcon.norse.alternateName, "AppIcon-Norse")
        XCTAssertEqual(AppIcon.vardr.alternateName, "AppIcon-Vardr")
        let read: [(String?, AppIcon)] = [
            (nil, .aurora), ("AppIcon-Norse", .norse), ("AppIcon-Vardr", .vardr),
            ("AppIcon", .aurora), ("AppIcon-norse", .aurora), ("AppIcon-Night", .aurora), ("", .aurora),
        ]
        for (name, icon) in read { XCTAssertEqual(AppIcon(alternateName: name), icon, "alternate name \(name ?? "nil")") }
        XCTAssertEqual(AppIcon.allCases.map(\.label), ["Aurora", "Norse", "Vardr Labs"], "the themes' labels")
        XCTAssertEqual(AppIcon.allCases.map(\.label), AppTheme.allCases.map(\.label))
    }

    /// Design 3 to 5: which icon is wanted, when the phone changes it, which row is checked, and what a tap or the
    /// switch stores.
    func testTheIconPlanGoldens() {
        let target: [(AppIconChoice, AppTheme, AppIcon)] = [
            (.match, .aurora, .aurora), (.match, .norse, .norse), (.match, .vardr, .vardr),
            (.aurora, .aurora, .aurora), (.aurora, .norse, .aurora), (.aurora, .vardr, .aurora),
            (.norse, .aurora, .norse), (.norse, .norse, .norse), (.norse, .vardr, .norse),
            (.vardr, .aurora, .vardr), (.vardr, .norse, .vardr), (.vardr, .vardr, .vardr),
        ]
        XCTAssertEqual(target.count, 12, "all 4 x 3 cells")
        for (choice, theme, icon) in target {
            XCTAssertEqual(AppIconPlan.target(choice, theme: theme), icon, "target \(choice) on \(theme)")
            XCTAssertEqual(AppIconPlan.checked(choice, theme: theme), icon, "the check \(choice) on \(theme)")
        }
        let change: [(AppIcon, AppIcon, AppIcon?)] = [
            (.aurora, .aurora, nil), (.aurora, .norse, .aurora), (.aurora, .vardr, .aurora),
            (.norse, .aurora, .norse), (.norse, .norse, nil), (.norse, .vardr, .norse),
            (.vardr, .aurora, .vardr), (.vardr, .norse, .vardr), (.vardr, .vardr, nil),
        ]
        XCTAssertEqual(change.count, 9, "all 9 pairs")
        for (wanted, current, result) in change {
            XCTAssertEqual(AppIconPlan.change(target: wanted, current: current), result, "change to \(wanted) from \(current)")
        }
        XCTAssertEqual(AppIconPlan.tap(.aurora), .aurora)
        XCTAssertEqual(AppIconPlan.tap(.norse), .norse)
        XCTAssertEqual(AppIconPlan.tap(.vardr), .vardr)
        let switched: [(Bool, AppTheme, AppIconChoice)] = [
            (true, .aurora, .match), (true, .norse, .match), (true, .vardr, .match),
            (false, .aurora, .aurora), (false, .norse, .norse), (false, .vardr, .vardr),
        ]
        for (on, theme, choice) in switched {
            XCTAssertEqual(AppIconPlan.matchSwitched(on: on, theme: theme), choice, "Match theme \(on) on \(theme)")
        }
    }

    /// Design 5's copy, verbatim (Android's twin compares its own with these, read from Appearance.swift).
    func testTheIconCopy() {
        XCTAssertEqual(AppIconCopy.title, "App icon")
        XCTAssertEqual(AppIconCopy.match, "Match theme")
        XCTAssertEqual(AppIconCopy.caption, "Your icon changes when you leave this page.")
        XCTAssertEqual(AppIconCopy.spoken(.aurora), "Aurora icon")
        XCTAssertEqual(AppIconCopy.spoken(.norse), "Norse icon")
        XCTAssertEqual(AppIconCopy.spoken(.vardr), "Vardr Labs icon")
    }

    /// Design 5 (.drafts/d216/art.py section 57): the preview tile's side and corner ratio, appended after D-226's last
    /// key, held to the touch row and to the 144 px preview image.
    func testTheIconPreviewMetrics() {
        XCTAssertEqual(ThemeArt.metrics["iconPreview"] ?? -1, 36)
        XCTAssertEqual(ThemeArt.metrics["iconPreviewRadius"] ?? -1, 0.225, accuracy: 1e-9)
        XCTAssertLessThanOrEqual(ThemeArt.m("iconPreview"), ThemeArt.m("touchTarget"))
        XCTAssertGreaterThanOrEqual(144, 3 * ThemeArt.m("iconPreview"))
    }

    // ---- D-216 Task 3: the App icon picker on Appearance and the switcher that changes the icon when the person leaves
    // the page (Design 4 and 5; source guards over the app folder, anchors copied from the files) ----

    /// Every Swift file under ios/Skipti/App (Relay/ included), by its file name (a path stripped of the folder's would
    /// break where /tmp resolves to /private/tmp, as in a worktree lane).
    private func appFolderSources() throws -> [(name: String, text: String)] {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let walker = try XCTUnwrap(FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil))
        var files: [(name: String, text: String)] = []
        for case let url as URL in walker where url.pathExtension == "swift" {
            files.append((url.lastPathComponent, try String(contentsOf: url, encoding: .utf8)))
        }
        XCTAssertGreaterThan(files.count, 40, "the walk found the app's files")
        return files
    }

    /// Design 4: the icon changes only when the person leaves Appearance. `setAlternateIconName(` is written once in the
    /// whole app, inside `AppIconSwitcher`; the switcher is called once, in the Appearance page's `.onDisappear`; never
    /// at launch (SkiptiApp.swift), never from the shell, never from a `scenePhase` handler, never on a theme tap.
    func testTheIconChangesOnlyWhenAppearanceCloses() throws {
        let files = try appFolderSources()
        let setters = files.filter { $0.text.contains("setAlternateIconName(") }.map(\.name)
        XCTAssertEqual(setters, ["Appearance.swift"], "the one file that sets the icon")
        let all = files.map(\.text).joined(separator: "\n")
        XCTAssertEqual(occurrences(all, "setAlternateIconName("), 1, "one call in the app")
        let switcher = try between(try appFile("Appearance.swift"), from: "enum AppIconSwitcher {", to: "\n}\n")
        XCTAssertEqual(occurrences(switcher, "setAlternateIconName("), 1, "inside AppIconSwitcher")
        // D-245 (U9, plan 1.3 W2): the welcome's close is Appearance's close: a third named site, `WelcomeLook.closed`,
        // the only call in Welcome.swift (it runs once the welcome's cover has gone, first run or replay).
        XCTAssertEqual(occurrences(all, "AppIconSwitcher.applyIfNeeded("), 2, "Appearance's leave and the welcome's close")
        let welcomeClose = try between(try appFile("Welcome.swift"), from: "    static func closed(defaults: UserDefaults = .standard,", to: "\n    }\n")
        XCTAssertTrue(welcomeClose.contains("{ AppIconSwitcher.applyIfNeeded(choice: $0, theme: $1) }"), welcomeClose)
        XCTAssertEqual(occurrences(try appFile("Welcome.swift"), "AppIconSwitcher."), 1, "one call in the welcome")
        // D-244 (ADR-0043; ADR-0041's App icon section amended), amended ON PURPOSE to exactly two named call sites: the
        // Appearance page's leave (`applyIfNeeded`, below) and the front moment of a change that arrived from the account
        // (`applyArrived`, once, in SettingsSync's `changeIfPending`), which acts only on the arrival's pending mark, with
        // SKIPTI in front and unlocked (SettingsSyncTests pin the decision and where the moment is called from).
        XCTAssertEqual(occurrences(all, "AppIconSwitcher.applyArrived("), 1, "the arrival's one call")
        let callers = files.filter { $0.text.contains("AppIconSwitcher.") }.map(\.name).sorted()
        XCTAssertEqual(callers, ["AccountHubScreen.swift", "SettingsSync.swift", "Welcome.swift"],
                       "the Appearance page, the front moment and the welcome's close")
        let sync = try appFile("SettingsSync.swift")
        XCTAssertEqual(occurrences(sync, "AppIconSwitcher."), 1, "one call in SettingsSync")
        let pending = try between(sync, from: "@MainActor private static func changeIfPending(store: LedgerStore, locked: Bool) {", to: "\n    }\n")
        XCTAssertTrue(pending.contains("AppIconSwitcher.applyArrived(choice: choice, theme: theme)"), pending)
        XCTAssertTrue(pending.contains("frontMoment(store: store, defaults: .standard, locked: locked,"), "through the pending decision")
        let arrived = try between(try appFile("Appearance.swift"), from: "static func applyArrived(choice: AppIconChoice, theme: AppTheme) -> Bool {", to: "\n    }\n")
        XCTAssertTrue(arrived.contains("UIApplication.shared.applicationState == .active"), "never in the background")
        XCTAssertTrue(arrived.contains("if active { applyIfNeeded(choice: choice, theme: theme) }"), "the same switch, in front only")
        let screen = try between(try appFile("AccountHubScreen.swift"), from: "struct SettingsSectionScreen: View {", to: "\n}\n")
        XCTAssertEqual(occurrences(screen, ".onDisappear"), 1)
        let leave = try between(screen, from: "        .onDisappear {\n", to: "\n        }\n")
        XCTAssertTrue(leave.contains("if section == .appearance {"), "only when Appearance is the page that closes: \(leave)")
        XCTAssertTrue(leave.contains("AppIconSwitcher.applyIfNeeded(choice: AppIconChoice.parse(iconRaw), theme: AppTheme.parse(themeRaw))"), leave)
        XCTAssertTrue(screen.contains("@AppStorage(AppIconChoice.storageKey) private var iconRaw = AppIconChoice.match.rawValue"))
        for file in ["SkiptiApp.swift", "HomeShell.swift"] {
            XCTAssertFalse(try appFile(file).contains("AppIconSwitcher"), "\(file): never at launch or from the shell")
            XCTAssertFalse(try appFile(file).contains("alternateIconName"), file)
        }
        // No scenePhase handler anywhere reaches the switcher: every `.onChange(of: scenePhase)` closure, read to its end.
        var handlers = 0
        for file in files {
            for chunk in file.text.components(separatedBy: ".onChange(of: scenePhase)").dropFirst() {
                handlers += 1
                let closure = chunk.components(separatedBy: "\n        }\n").first ?? chunk
                XCTAssertFalse(closure.contains("AppIconSwitcher"), "\(file.name): a scenePhase handler changes the icon")
            }
        }
        XCTAssertGreaterThanOrEqual(handlers, 2, "the walk saw the app's scenePhase handlers")
        // D-244: the app's scene handler reaches the icon only through the front moment, after the lock's decision.
        let app = try appFile("SkiptiApp.swift")
        let scene = try between(app, from: ".onChange(of: scenePhase) { _, phase in", to: "\n                }\n")
        let came = try XCTUnwrap(scene.range(of: "if phase == .active { lock.cameToFront() }"))
        let front = try XCTUnwrap(scene.range(of: "if phase == .active { settingsFrontMoment() }"))
        XCTAssertLessThan(came.lowerBound, front.lowerBound, "the lock decides first")
        // The theme cards and the picker's rows store a choice; none of them changes the icon.
        let theme = try between(screen, from: "case .theme:", to: "case .appearanceMode:")
        XCTAssertFalse(theme.contains("AppIconSwitcher"), "a theme tap never changes the icon")
        let section = try between(try appFile("AccountHubScreen.swift"), from: "struct AppIconSection: View {", to: "\n}\n")
        XCTAssertFalse(section.contains("AppIconSwitcher"), "a picker tap never changes the icon")
        XCTAssertFalse(section.contains("onDisappear"), "the page's leaving, not the section's")
    }

    /// Design 4: the switcher does nothing unless the phone can change icons and the app is in front; it reads the icon
    /// the phone shows, lets `AppIconPlan.change(` decide (nil: no call, so no alert), and calls once with the alternate
    /// name (nil for Aurora). A failure shows nothing; the next leave tries again.
    func testTheSwitcherChecksItCanAndIsActive() throws {
        let appearance = try appFile("Appearance.swift")
        XCTAssertTrue(appearance.contains("@MainActor\nenum AppIconSwitcher {"), "on the main actor")
        let switcher = try between(appearance, from: "enum AppIconSwitcher {", to: "\n}\n")
        XCTAssertTrue(switcher.contains("static func applyIfNeeded(choice: AppIconChoice, theme: AppTheme) {"), switcher)
        let order = ["app.supportsAlternateIcons", "app.applicationState == .active", "else { return }",
                     "AppIcon(alternateName: app.alternateIconName)",
                     "AppIconPlan.change(target: AppIconPlan.target(choice, theme: theme), current: current)",
                     "app.setAlternateIconName(change.alternateName"]
        let at = try order.map { try XCTUnwrap(switcher.range(of: $0), "AppIconSwitcher: \($0)").lowerBound }
        XCTAssertEqual(at, at.sorted(), "can, is active, the icon shown, the plan decides, then the one call")
        XCTAssertTrue(switcher.contains("guard let change = AppIconPlan.change("), "nil from the plan: no call, no alert")
        XCTAssertEqual(occurrences(switcher, "guard "), 2, "the two guards and nothing else returning early")
        for banned in ["UserDefaults", "Task {", "DispatchQueue", "asyncAfter", "alert"] {
            XCTAssertFalse(switcher.contains(banned), "AppIconSwitcher: \(banned)")
        }
    }

    /// Design 5: in the Appearance page the "App icon" section comes after the Light or dark control and before the Text
    /// size label: its label, ONE list card (the Match theme switch row, then the three icon rows with the check on
    /// `AppIconPlan.checked`), the caption under the card. Each preview is the icon's own image in a rounded square of
    /// `iconPreview` and `iconPreviewRadius`, the same shape in every theme (never a `TileShape`), decorative; each icon
    /// row is spoken "<Theme> icon" with the selected state. Absent where the phone cannot change icons.
    func testThePickerSitsBetweenModeAndTextSize() throws {
        let hub = try appFile("AccountHubScreen.swift")
        let screen = try between(hub, from: "struct SettingsSectionScreen: View {", to: "\n}\n")
        let page = try between(screen, from: "case .theme:", to: "case .appLock, .privacy")
        let order = ["ThemeCard(theme: theme,", "Text(verbatim: AppearanceCopy.cards[1]).sectionLabel()", "ModeControl(theme: theme,",
                     "if UIApplication.shared.supportsAlternateIcons {\n                AppIconSection()\n            }",
                     "case .textSize:", "Text(verbatim: AppearanceCopy.cards[2]).sectionLabel()", "HapticsRow()", "MotionRow()"]
        let at = try order.map { try XCTUnwrap(page.range(of: $0), "Appearance: \($0)").lowerBound }
        XCTAssertEqual(at, at.sorted(), "theme, Light or dark, App icon, Text size, Haptics, Less motion")
        XCTAssertEqual(occurrences(hub, "AppIconSection()"), 1, "one place")
        XCTAssertEqual(SettingsMap.items(.appearance, signedIn: false), [.theme, .appearanceMode, .textSize], "the map is unchanged")

        let section = try between(hub, from: "struct AppIconSection: View {", to: "\n}\n")
        XCTAssertTrue(section.contains("@AppStorage(AppIconChoice.storageKey) private var iconRaw = AppIconChoice.match.rawValue"))
        // D-234 (A2, decision 80): one card; its header row says "App icon", the current choice and the chevron; the four
        // rows open under it in the same card, a hairline before each; the caption only while open.
        let parts = ["VStack(alignment: .leading, spacing: 8) {", "ListCard {",
                     "withAnimation(AppIconPlan.expandSeconds(moves: moves).map { .easeInOut(duration: $0) }) { expanded.toggle() }",
                     "ListRow(title: AppIconCopy.title, trailing: .disclosure(AppIconCopy.choice(choice), expanded: expanded))",
                     "if expanded {", "Rectangle().fill(Tokens.hairline).frame(height: 1)",
                     "ListRow(leading: AnyView(AppIconPreview(icon: AppIcon.of(theme))), title: AppIconCopy.match,",
                     // D-244 (ADR-0043), amended on purpose: both stores go through the one write of a synced setting.
                     "trailing: .toggle(Binding(get: { choice == .match }, set: { SettingsSync.set(AppIconChoice.storageKey, AppIconPlan.matchSwitched(on: $0, theme: theme).rawValue) })))",
                     "ForEach(AppIcon.allCases, id: \\.self) { icon in", "SettingsSync.set(AppIconChoice.storageKey, AppIconPlan.tap(icon).rawValue)",
                     "ListRow(leading: AnyView(AppIconPreview(icon: icon)), title: icon.label, trailing: .check(icon == checked))",
                     ".accessibilityLabel(Text(verbatim: AppIconCopy.spoken(icon)))",
                     ".accessibilityAddTraits(icon == checked ? .isSelected : [])", ".transition(.opacity)",
                     "CardCaption(AppIconCopy.caption)"]
        let placed = try parts.map { try XCTUnwrap(section.range(of: $0), "AppIconSection: \($0)").lowerBound }
        XCTAssertEqual(placed, placed.sorted(), "one card: the header, then the open rows; the caption last")
        XCTAssertEqual(occurrences(section, "ListCard {"), 1, "one card")
        XCTAssertEqual(occurrences(section, "ListRow("), 3, "the header, the switch row and the icon rows' one row")
        XCTAssertEqual(occurrences(section, "if expanded {"), 2, "the rows and the caption, only while open")
        XCTAssertFalse(section.contains(".sectionLabel()"), "no label above the card: the header row says it")
        XCTAssertTrue(section.contains("@State private var expanded = false"), "closed each time the page opens, never stored")
        XCTAssertTrue(section.contains("@Environment(\\.motionReduced) private var reduceMotion"))
        XCTAssertTrue(section.contains("let moves = MotionClock.momentTicks(reduceMotion: reduceMotion, active: true)"))
        XCTAssertTrue(section.contains("let checked = AppIconPlan.checked(choice, theme: theme)"))
        for banned in [".disabled(", "enabled: false", "if choice", ".hidden(", "opacity(", "repeatForever"] {
            XCTAssertFalse(section.contains(banned), "no disabled or hidden rows, no looping animation: \(banned)")
        }

        let preview = try between(hub, from: "struct AppIconPreview: View {", to: "\n}\n")
        for needle in ["let side = ThemeArt.m(\"iconPreview\")", "Image(icon.previewAsset)", ".resizable()",
                       ".frame(width: side, height: side)",
                       ".clipShape(RoundedRectangle(cornerRadius: side * ThemeArt.m(\"iconPreviewRadius\"), style: .continuous))",
                       ".accessibilityHidden(true)"] {
            XCTAssertTrue(preview.contains(needle), "AppIconPreview: \(needle)")
        }
        for banned in ["TileShape", "TileClip", "panelShape", "controlShape", "AppearanceState", "theme"] {
            XCTAssertFalse(preview.contains(banned), "the preview keeps the icon's own shape in every theme: \(banned)")
        }
        XCTAssertEqual(AppIcon.allCases.map(\.previewAsset), ["IconPreview-Aurora", "IconPreview-Norse", "IconPreview-Vardr"])
        for icon in AppIcon.allCases {
            XCTAssertNotNil(UIImage(named: icon.previewAsset), "\(icon.previewAsset) is in the catalog")
        }
        // S2's row takes the leading view where the tile would go (Android's `leading` slot, its twin).
        let row = try between(try appFile("ListRows.swift"), from: "struct ListRow: View {", to: "\n}\n")
        XCTAssertTrue(row.contains("var leading: AnyView? = nil"), row)
        XCTAssertTrue(row.contains("if let glyph { SettingsTile(glyph: glyph) }\n            if let leading { leading }"), row)
    }

    /// D-234 (A2, decision 80): the header's words and its motion. The value is the current choice; the chevron turns
    /// 90 degrees while open; VoiceOver reads "App icon", then "<choice>, collapsed" (or expanded), as a button. The
    /// rows open over the switch's own travel when art may move, and snap (nil: no animation) when it may not. Android's
    /// twin is AppearanceTest.the_app_icon_header_opens_and_closes.
    func testTheAppIconHeaderOpensAndCloses() throws {
        XCTAssertEqual(AppIconChoice.allCases.map(AppIconCopy.choice), ["Match theme", "Aurora", "Norse", "Vardr Labs"])
        XCTAssertEqual(AppIconPlan.expandSeconds(moves: true), 0.2)
        XCTAssertEqual(AppIconPlan.expandSeconds(moves: true), Double(ThemeArt.m("switchTravel")), "the switch's own travel")
        XCTAssertNil(AppIconPlan.expandSeconds(moves: false), "the motion door shut: a snap")
        XCTAssertEqual(RowDisclosure.chevronDegrees(expanded: false), 0)
        XCTAssertEqual(RowDisclosure.chevronDegrees(expanded: true), 90)
        XCTAssertEqual(RowDisclosure.spokenValue("Match theme", expanded: false), "Match theme, collapsed")
        XCTAssertEqual(RowDisclosure.spokenValue("Norse", expanded: true), "Norse, expanded")
        let row = try between(try appFile("ListRows.swift"), from: "struct ListRow: View {", to: "\n}\n")
        for needle in [
            "case .disclosure(let value, let expanded):\n            line\n                .accessibilityElement(children: .ignore)\n                .accessibilityLabel(Text(verbatim: spoken))\n                .accessibilityValue(Text(verbatim: RowDisclosure.spokenValue(value, expanded: expanded)))\n                .accessibilityAddTraits(.isButton)",
            ".rotationEffect(.degrees(RowDisclosure.chevronDegrees(expanded: expanded)))",
        ] {
            XCTAssertTrue(row.contains(needle), "ListRow: \(needle)")
        }
    }

    /// Design 4: no route around the system alert. Nothing in the app reaches a private selector or the underscored
    /// setter (the old route stopped working in iOS 26.1), and the icons are never written into Info.plist by hand.
    /// Every `perform(` is banned except SKIPTI's own `perform(redistributions:)` (RemovalFlow's `RemovalContext`), so a
    /// selector built anywhere and performed through a variable (`x.perform(s)`) fails too (Task 3's review, M1); the
    /// runtime's own dispatch (`objc_msgSend`, `class_getInstanceMethod`) is banned outright.
    func testNoAlertTricks() throws {
        let perform = try NSRegularExpression(pattern: #"perform\((?!redistributions:)"#)
        var own = 0
        for file in try appFolderSources() {
            for banned in ["NSSelectorFromString", "_setAlternateIconName", "perform(Selector", "perform(#selector",
                           "UIApplication.shared.perform(", "app.perform(", "Selector((\"", "objc_msgSend",
                           "class_getInstanceMethod"] {
                XCTAssertFalse(file.text.contains(banned), "\(file.name): \(banned)")
            }
            let range = NSRange(file.text.startIndex..., in: file.text)
            XCTAssertEqual(perform.numberOfMatches(in: file.text, range: range), 0, "\(file.name): a perform( that is not SKIPTI's own")
            own += file.text.components(separatedBy: "perform(redistributions:").count - 1
        }
        XCTAssertGreaterThanOrEqual(own, 2, "the walk saw RemovalFlow's own perform(redistributions:), so the pattern ran")
        let plist = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/Info.plist")
        XCTAssertFalse(try String(contentsOf: plist, encoding: .utf8).contains("CFBundleIcons"), "actool writes the icons' entries")
    }
}
