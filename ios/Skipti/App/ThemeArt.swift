import SwiftUI
import UIKit

/// D-213 (E2, ADR-0041 "Theme art", the founder, 2026-10-15): the runes the
/// themes draw. D-215: from our own Vardr Runes Carved Regular, bundled
/// (`TypeRole.rune`; ThemeArtTests proves every one), so both phones draw the
/// same shapes. Android's twin is `Runes` in ThemeArt.kt; its ThemeArtTest
/// compares the two files.
enum Runes {
    static let balance = "\u{16A0}" // Fehu
    static let expenses = "\u{16B7}" // Gebo
    static let groups = "\u{16D7}" // Mannaz
    static let settle = "\u{16CF}" // Tiwaz
    static let separator = "\u{16EB}" // single punctuation
    static let sectionMark = "\u{16DF}" // Othala
    static let ornament = "\u{16DC}" // Ingwaz
    /// The 24 runes of the Elder Futhark, in order (D-214 draws its drift from them).
    static let futhark = [
        "\u{16A0}", "\u{16A2}", "\u{16A6}", "\u{16A8}", "\u{16B1}", "\u{16B2}", "\u{16B7}", "\u{16B9}",
        "\u{16BA}", "\u{16BE}", "\u{16C1}", "\u{16C3}", "\u{16C7}", "\u{16C8}", "\u{16C9}", "\u{16CA}",
        "\u{16CF}", "\u{16D2}", "\u{16D6}", "\u{16D7}", "\u{16DA}", "\u{16DC}", "\u{16DE}", "\u{16DF}",
    ]
    /// Every rune SKIPTI draws.
    static let all = futhark + [separator]
}

/// D-213: the art's own colours and sizes (the Look Book v2.1), derived and
/// measured by .drafts/d213/art.py; never retype them. AppearanceTests checks
/// every pair they make.
enum ThemeArt {
    static let hex: [String: String] = [
        "norseEdge": "#6B5426", "norseCardTop": "#1A1710", "norseCardBottom": "#0F1015", "norseSilver": "#9AA2AE",
        "diamondTop": "#F2D37A", "diamondBottom": "#B4851F",
        "boltDark0": "#E3EAF0", "boltDark1": "#9DB0BF", "boltDark2": "#6C7F8E", "boltDark3": "#C3CFD8",
        "boltLight0": "#8DA0AF", "boltLight1": "#4C6172", "boltLight2": "#24333F", "boltLight3": "#6C7F8E",
        "runeUnlit": "#4A4740", "norseMeterGap": "#1A1A1C",
        // D-224 (.drafts/d224/art.py section 37): the Norse check's dark gold well (LB 247).
        "norseCheckFill": "#18120A",
    ]

    /// Points, fractions and alphas. D-226 (.drafts/d226/art.py section 46, the founder's Q8 a): the art lengths D-213
    /// copied 1:1 from the Look Book's px are scaled by its small-type scale (x 1.28): chamfer, frameInset, frameLength,
    /// gridPitch, gridDot, diamondRadius, tileRadius, ornamentRune and pillChamfer. tabRune 34 (G1, the founder's look: the runes read too small in their tile): Vardr Runes Carved inks 0.70 em, so 34 pt stands 23.8 pt in the 30 pt slot.
    static let metrics: [String: Double] = [
        "chamfer": 10, "frameInset": 4, "frameLength": 12, "frameAlpha": 0.7,
        "gridPitch": 18, "gridDot": 0.77, "gridAlpha": 0.2025,
        "diamondSide": 64, "diamondRadius": 8, "tileRadius": 3, "boltWidth": 72, "boltHeight": 64,
        "boltStartX": 0.183, "boltStartY": -0.119, "boltEndX": 0.817, "boltEndY": 1.119,
        "boltDark1At": 0.38, "boltDark2At": 0.62, "boltLight1At": 0.45, "boltLight2At": 0.70,
        "reticleDark": 0.18, "reticleLight": 0.25, "outlineLight": 0.75, "outlineDark": 0.5,
        "ornamentRune": 17, "tabRune": 34, "reticleGlyph": 32,
        "pillChamfer": 5, "pillOutline": 0.4,
        "ringBox": 137, "ringRadius": 62, "ringStroke": 6, "runeRingRadius": 60, "runeRingSize": 14,
        "meterBig": 10, "meterBigVardr": 7, "meterSmall": 6, "meterSmallVardr": 4, "meterSegment": 11, "meterGap": 2,
        "progressDot": 7, "progressDotGap": 7, "seal": 44, "groupTile": 48,
        // D-222 (.drafts/d222/art.py sections 24 to 28): You's icon tiles and list rhythm, the theme cards and swatches,
        // the mode control (its touch row, track, inset and the chosen segment's shadow) and the text-size slider.
        "settingsTile": 33, "settingsGlyph": 19, "listPadH": 15, "listPadV": 3, "rowPadV": 10, "rowGap": 13,
        "themePad": 8, "themeGap": 6, "swatchHeight": 59, "swatchRadius": 10, "swatchRune": 20, "swatchRuneTracking": 0.12,
        "swatchGridPitch": 8, "swatchGridDot": 0.9, "swatchMarkInset": 6, "swatchMarkLength": 10,
        "modeTrack": 40, "modeInset": 4, "touchTarget": 48, "segmentShadowAlpha": 0.15, "segmentShadowRadius": 2,
        "segmentShadowY": 1, "sliderTrack": 5, "sliderThumb": 20, "sliderRing": 2.5,
        // D-223 (.drafts/d223/art.py sections 30, 32 and 34): the ledger grid and card, Lately's rows (the row gap 11 so
        // stacked Norse diamonds clear), the empty state's icon and the Groups card's spacing.
        "ledgerTile": 33, "ledgerTileGap": 9, "ledgerCardGap": 6, "ledgerGridGap": 10, "latelyAvatar": 33,
        "latelyAvatarGap": 12, "latelyRowGap": 11, "emptyIcon": 44, "groupsCardSpacing": 12,
        // D-224 (.drafts/d224/art.py sections 37 to 39): the big done check (in D-221's ring box), the small done toast
        // (the Look Book's x 1.28) and where the toast sits: its bottom edge 104 above the bar row's bottom, clear of the
        // tallest + face by 8, else 16 above the safe area.
        "doneCheck": 104, "doneCircleRadius": 48, "doneCircleStroke": 3, "doneTickStroke": 6, "toastRadius": 18,
        "toastPadV": 12, "toastPadH": 15, "toastGap": 10, "toastDot": 26, "toastGlyph": 15, "toastCheckStroke": 3.5,
        "toastInset": 16, "toastClearOfBar": 104, "toastShadowAlpha": 0.14, "toastShadowRadius": 9, "toastShadowY": 6,
        // D-225 (.drafts/d225/art.py sections 41 to 45): the list row's line gap, the drawn switch (track, thumb, inset,
        // off border, slide time), the emoji grid (columns, gap, glyph, chosen border and corner), the picture's
        // placeholder ratios, the lock screen's glyph and the clear cross.
        "rowLineGap": 2, "switchWidth": 51, "switchHeight": 31, "switchThumb": 27, "switchInset": 2, "switchBorder": 1.5,
        "switchTravel": 0.2, "emojiColumns": 6, "emojiGap": 8, "emojiGlyph": 24, "emojiBorder": 2, "emojiRadius": 8,
        "avatarGlyph": 0.5, "avatarEmoji": 0.55, "lockGlyph": 40, "clearGlyph": 18,
        // D-226 (.drafts/d226/art.py sections 46, 51 and 53): the Norse hero's inner frame and its watermark rune (size,
        // its trailing and top place, scaled x 1.28), and Aurora's glass (iOS's film over its material, Android's film
        // with and without the art blur, iOS's rim, Android's rim in light and dark (the dark one measured on iOS in
        // Task 0), the card shadow's sigma, offset and alpha).
        "heroFrameInset": 6, "heroWatermark": 118, "heroWatermarkTrailing": 13, "heroWatermarkTop": -8, "glassFilm": 0.78,
        "glassFilmNoBlur": 0.82, "glassRim": 0.35, "glassRimLight": 0.35, "glassRimDark": 0.35, "glassShadowSigma": 12,
        "glassShadowY": 4, "glassShadowAlpha": 0.06,
        // D-216 (.drafts/d216/art.py section 57): the App icon rows' preview tile, its side and its corner as a share of
        // the side (Apple's icon ratio; the same shape in every theme).
        "iconPreview": 36, "iconPreviewRadius": 0.225,
        // D-243 (T7, .drafts/d214/motion.py section 1f; the approved Vibrant mock-up's drop-shadow(0 0 7px accent 0.18)):
        // Norse's static glow outside a card's edge: its peak alpha, its Gaussian sigma (pt, the CSS blur / 2) and the
        // width of each drawn band (pt). Sigma 3.0, not the mock-up's 3.5: the gap audit's tightest page-ground text
        // beside a card is 6 pt (iOS's Add a person labels; .drafts/d243/gap-audit.md), so the reach must fit 6 (plan
        // R2 option 1). Twelve bands, 6 pt: page-ground text keeps that far from a Norse card.
        "norseEdgeGlow": 0.18, "norseEdgeGlowSigma": 3.0, "norseEdgeGlowBand": 0.5,
    ]

    /// G1: a rune's line box runs ascent 900 to descent 250 (units per 1000), so its middle sits 325 above the baseline and
    /// its ink (cap 0 to 700) middle 350: the tab rune moves down by this share of its size to centre by its ink.
    static let runeInkShiftEm: CGFloat = (350 - (900 - 250) / 2) / 1000
    static var tabRuneInkShift: CGFloat { m("tabRune") * runeInkShiftEm }

    static func uiColor(_ name: String) -> UIColor { UIColor(hex: hex[name]!) }
    static func color(_ name: String) -> Color { Color(uiColor(name)) }
    static func m(_ name: String) -> CGFloat { CGFloat(metrics[name]!) }

    static var norseEdge: Color { color("norseEdge") }
    static var norseCardTop: Color { color("norseCardTop") }
    static var norseCardBottom: Color { color("norseCardBottom") }
    static var norseSilver: Color { color("norseSilver") }
    static var diamondTop: Color { color("diamondTop") }
    static var diamondBottom: Color { color("diamondBottom") }

    /// The hex bolt's brushed steel: the Look Book's dark stops, or its darker light ones (D-213).
    static func boltStops(dark: Bool) -> [Gradient.Stop] {
        let p = dark ? "boltDark" : "boltLight"
        return [
            .init(color: color(p + "0"), location: 0),
            .init(color: color(p + "1"), location: m(p + "1At")),
            .init(color: color(p + "2"), location: m(p + "2At")),
            .init(color: color(p + "3"), location: 1),
        ]
    }

    static func reticleAlpha(dark: Bool) -> Double { Double(m(dark ? "reticleDark" : "reticleLight")) }
}

/// D-224 (L49, .drafts/d224/art.py section 37): the done check's tick, three points in the check's 104 box, drawn with
/// round caps and joins. (The view itself, `DoneCheck`, lives in DoneMoments.swift; Android's ThemeArtTest reads
/// this line.)
extension DoneCheck {
    static let tick: [(Double, Double)] = [(32, 54), (46, 67), (73, 39)]
}

/// D-213: the art's shapes as vertex lists (testable), drawn by the shapes below.
enum ArtGeometry {
    /// A rectangle with each corner cut at `chamfer` (clamped to half the short side).
    static func notched(_ r: CGRect, chamfer: CGFloat) -> [CGPoint] {
        let c = min(chamfer, min(r.width, r.height) / 2)
        return [
            CGPoint(x: r.minX + c, y: r.minY), CGPoint(x: r.maxX - c, y: r.minY),
            CGPoint(x: r.maxX, y: r.minY + c), CGPoint(x: r.maxX, y: r.maxY - c),
            CGPoint(x: r.maxX - c, y: r.maxY), CGPoint(x: r.minX + c, y: r.maxY),
            CGPoint(x: r.minX, y: r.maxY - c), CGPoint(x: r.minX, y: r.minY + c),
        ]
    }

    /// D-243 (T7, .drafts/d214/motion.py `edge_bands`, section 1f): Norse's card-edge glow as concentric bands, the
    /// approved mock-up's CSS drop-shadow (blur 7 = 2 sigma) outside a straight edge. Band k is centred `band (k + 0.5)`
    /// out from the edge at `peak Phi(-d / sigma)` = `peak erfc(d / (sigma sqrt 2)) / 2`, until a band would fall under
    /// one 8-bit step (1/255). The table's 0.18, 3.0 and 0.5 give twelve bands, 6 pt. Android's twin ports erfc.
    static func glowBands(peak: Double = ThemeArt.metrics["norseEdgeGlow"]!,
                          sigma: Double = ThemeArt.metrics["norseEdgeGlowSigma"]!,
                          band: Double = ThemeArt.metrics["norseEdgeGlowBand"]!) -> [(offset: CGFloat, alpha: Double)] {
        guard peak > 0, sigma > 0, band > 0 else { return [] }
        var out: [(offset: CGFloat, alpha: Double)] = []
        while true {
            let d = band * (Double(out.count) + 0.5)
            let a = peak * erfc(d / (sigma * 2.0.squareRoot())) / 2
            if a < 1.0 / 255 { return out }
            out.append((CGFloat(d), a))
        }
    }

    /// D-243 (T7): how far the glow reaches past a card's edge, the bands' outer edge (6 pt). It is also motion.py's
    /// CLEAR DISTANCE: every band fails textDim over a flaring rune, so page-ground text keeps at least this far from a
    /// Norse card (.drafts/d243/gap-audit.md).
    static var glowReach: CGFloat { CGFloat(glowBands().count) * ThemeArt.m("norseEdgeGlowBand") }

    /// D-243 (T7): the notched outline grown `d` outward with mitred joins: the box `d` larger on every side and the
    /// chamfer (clamped first, as `notched`'s) `c + d (2 - sqrt 2)`, so every side, the 45 degree cuts included, runs
    /// exactly `d` from the card's own (.drafts/d243/t7/geometry.py derives it by offsetting each edge line).
    static func grownNotched(_ r: CGRect, chamfer: CGFloat, by d: CGFloat) -> [CGPoint] {
        let c = min(chamfer, min(r.width, r.height) / 2)
        return notched(r.insetBy(dx: -d, dy: -d), chamfer: c + d * (2 - 2.0.squareRoot()))
    }

    /// The box's edge midpoints: a square turned 45 degrees, drawn upright.
    static func diamond(_ r: CGRect) -> [CGPoint] {
        [CGPoint(x: r.midX, y: r.minY), CGPoint(x: r.maxX, y: r.midY),
         CGPoint(x: r.midX, y: r.maxY), CGPoint(x: r.minX, y: r.midY)]
    }

    /// The Look Book's bolt head (its clip-path).
    static func hexagon(_ r: CGRect) -> [CGPoint] {
        [(0.25, 0.02), (0.75, 0.02), (1, 0.5), (0.75, 0.98), (0.25, 0.98), (0, 0.5)].map {
            CGPoint(x: r.minX + r.width * $0.0, y: r.minY + r.height * $0.1)
        }
    }

    /// D-226 (L35, Q18 a, .drafts/d226/art.py section 52): the Norse card's edge on its four straight sides only (top,
    /// right, bottom, left), as the Look Book's clip-path cuts the card's border at the corners. Each segment runs `inset`
    /// in from its side (the 1 pt line's centre, so the line lies inside the shape: D28) and ends where that centre line
    /// meets the corner's diagonal. The chamfer is clamped to half the short side, as `notched`'s.
    static func notchEdges(_ r: CGRect, chamfer: CGFloat, inset i: CGFloat = 0.5) -> [(CGPoint, CGPoint)] {
        let c = min(chamfer, min(r.width, r.height) / 2)
        return [
            (CGPoint(x: r.minX + c - i, y: r.minY + i), CGPoint(x: r.maxX - c + i, y: r.minY + i)),
            (CGPoint(x: r.maxX - i, y: r.minY + c - i), CGPoint(x: r.maxX - i, y: r.maxY - c + i)),
            (CGPoint(x: r.maxX - c + i, y: r.maxY - i), CGPoint(x: r.minX + c - i, y: r.maxY - i)),
            (CGPoint(x: r.minX + i, y: r.maxY - c + i), CGPoint(x: r.minX + i, y: r.minY + c - i)),
        ]
    }

    /// Eight strokes: an L at each corner, `inset` in, each arm `length` long.
    static func frameMarks(_ r: CGRect, inset i: CGFloat, length l: CGFloat) -> [(CGPoint, CGPoint)] {
        let (x0, y0, x1, y1) = (r.minX + i, r.minY + i, r.maxX - i, r.maxY - i)
        return [
            (CGPoint(x: x0, y: y0), CGPoint(x: x0 + l, y: y0)), (CGPoint(x: x0, y: y0), CGPoint(x: x0, y: y0 + l)),
            (CGPoint(x: x1, y: y0), CGPoint(x: x1 - l, y: y0)), (CGPoint(x: x1, y: y0), CGPoint(x: x1, y: y0 + l)),
            (CGPoint(x: x1, y: y1), CGPoint(x: x1 - l, y: y1)), (CGPoint(x: x1, y: y1), CGPoint(x: x1, y: y1 - l)),
            (CGPoint(x: x0, y: y1), CGPoint(x: x0 + l, y: y1)), (CGPoint(x: x0, y: y1), CGPoint(x: x0, y: y1 - l)),
        ]
    }

    /// A dot at the centre of each `pitch` cell whose centre lies inside `size`.
    static func gridDots(_ size: CGSize, pitch: CGFloat) -> [CGPoint] {
        guard pitch > 0 else { return [] }
        var dots: [CGPoint] = []
        var y = pitch / 2
        while y < size.height {
            var x = pitch / 2
            while x < size.width {
                dots.append(CGPoint(x: x, y: y))
                x += pitch
            }
            y += pitch
        }
        return dots
    }

    /// A closed polygon; with `radius` > 0 each corner is rounded by a curve that starts `radius` along each edge.
    static func roundedPolygon(_ p: [CGPoint], radius r: CGFloat) -> Path {
        var path = Path()
        guard p.count > 2 else { return path }
        func toward(_ a: CGPoint, _ b: CGPoint) -> CGPoint {
            let dx = b.x - a.x, dy = b.y - a.y
            let len = max(hypot(dx, dy), .ulpOfOne)
            let t = min(r, len / 2) / len
            return CGPoint(x: a.x + dx * t, y: a.y + dy * t)
        }
        for i in p.indices {
            let v = p[i], prev = p[(i + p.count - 1) % p.count], next = p[(i + 1) % p.count]
            let a = toward(v, prev), b = toward(v, next)
            if i == 0 { path.move(to: a) } else { path.addLine(to: a) }
            if r > 0 { path.addQuadCurve(to: b, control: v) } else { path.addLine(to: b) }
        }
        path.closeSubpath()
        return path
    }
}

struct NotchedRectangle: Shape {
    var chamfer: CGFloat
    func path(in rect: CGRect) -> Path { ArtGeometry.roundedPolygon(ArtGeometry.notched(rect, chamfer: chamfer), radius: 0) }
}

/// D-243 (T7, the approved Vibrant mock-up, .drafts/u24/norse-vibrant.html): Norse's static gold glow OUTSIDE a card's
/// notched edge, beneath its fill: `ArtGeometry.glowBands()` stroked as concentric outlines `norseEdgeGlowBand` wide,
/// each grown from the card's by its band's offset (`grownNotched`), the accent at the band's alpha. No blur and no
/// shadow, so both phones draw the same bands. It fills the frame it is given (the card's) plus `glowReach` on every
/// side; nothing inside the card is drawn. Decorative.
struct NorseEdgeGlow: View {
    var body: some View {
        let reach = ArtGeometry.glowReach
        Canvas { ctx, size in
            let card = CGRect(origin: .zero, size: size).insetBy(dx: reach, dy: reach)
            let style = StrokeStyle(lineWidth: ThemeArt.m("norseEdgeGlowBand"), lineJoin: .miter)
            for band in ArtGeometry.glowBands() {
                let outline = ArtGeometry.grownNotched(card, chamfer: ThemeArt.m("chamfer"), by: band.offset)
                ctx.stroke(ArtGeometry.roundedPolygon(outline, radius: 0), with: .color(Tokens.accent.opacity(band.alpha)), style: style)
            }
        }
        .padding(-reach)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

extension View {
    /// D-243 (T7): Norse's edge glow behind a card that draws its own art (the two heroes: outside their clipped
    /// `HeroArt`, so the clip leaves it whole). Nothing in Aurora and Vardr Labs.
    func norseEdgeGlow() -> some View {
        background {
            if CardStyle.of(AppearanceState.shared.theme) == .notched { NorseEdgeGlow() }
        }
    }
}

/// D-226 (L35): the four straight edges of a notched card (`ArtGeometry.notchEdges`), stroked 1 pt by the caller and
/// clipped to the notched fill, so the corner cut is the Look Book's. The chamfer comes from the caller.
struct NotchEdges: Shape {
    var chamfer: CGFloat
    var inset: CGFloat = 0.5
    func path(in rect: CGRect) -> Path {
        var path = Path()
        for (a, b) in ArtGeometry.notchEdges(rect, chamfer: chamfer, inset: inset) {
            path.move(to: a)
            path.addLine(to: b)
        }
        return path
    }
}

/// D-226 (L31, .drafts/d226/art.py section 49): a CSS `linear-gradient(<degrees>deg, ...)` on a `width` x `height` box as
/// SwiftUI unit points. CSS's direction is (sin, -cos) of the angle and its gradient line is |w sin| + |h cos| long
/// through the centre, so the ends can lie outside the box. 150 on 72 x 64 gives C1's bolt points. An empty box gives
/// the centre twice (nothing to draw).
enum CssGradient {
    static func unitPoints(degrees: Double, width: Double, height: Double) -> (start: UnitPoint, end: UnitPoint) {
        guard width > 0, height > 0 else { return (.center, .center) }
        let r = degrees * .pi / 180
        let dx = sin(r), dy = -cos(r)
        let half = (abs(width * dx) + abs(height * dy)) / 2
        return (UnitPoint(x: 0.5 - dx * half / width, y: 0.5 - dy * half / height),
                UnitPoint(x: 0.5 + dx * half / width, y: 0.5 + dy * half / height))
    }
}

/// G1 (the founder, 2026-10-15): the shape of a button or an input. Norse notches it like its cards (chamfer 10 since D-226); Aurora and
/// Vardr Labs keep their rounded rectangle (a call site may name its own radius for them). The ONE decision: every button
/// style and input background asks for it, so a new one cannot miss it (ThemeArtTests reads the sources).
enum ControlShapeKind: Equatable {
    case rounded(CGFloat)
    case notched(CGFloat)
}

extension ThemeArt {
    static func controlKind(_ theme: AppTheme, radius: CGFloat? = nil) -> ControlShapeKind {
        theme == .norse ? .notched(m("chamfer")) : .rounded(radius ?? theme.buttonRadius)
    }

    /// G1 fix 1 (the founder: "anything rectangular in Norse"): an inner panel or stat tile takes the cards' notch too.
    /// The cut is one rule, here: `chamfer` on a panel taller than 50 pt, else 8 (so a short one is not over-cut);
    /// the geometry clamps it to half the short side. Aurora and Vardr Labs keep the call site's radius. D-226 (art.py
    /// section 46): both numbers scaled with the chamfer (8 to 10), the rule's reason kept (the cut never more than a
    /// fifth of the panel: 10 / 0.20 = 50; 6 x 1.28 = 7.68, so 8).
    static let panelTallerThan: CGFloat = 50
    static let panelSmallChamfer: CGFloat = 8

    static func panelChamfer(height: CGFloat) -> CGFloat { height > panelTallerThan ? m("chamfer") : panelSmallChamfer }

    /// D-220 (L20, the Look Book squares everything in a Vardr Labs card): outside Norse a panel takes
    /// `min(radius, theme.cardRadius)`: Aurora keeps the call site's 10 to 18 (under its 20), Vardr Labs is square.
    static func panelKind(_ theme: AppTheme, radius: CGFloat, height: CGFloat) -> ControlShapeKind {
        theme == .norse ? .notched(panelChamfer(height: height)) : .rounded(min(radius, theme.cardRadius))
    }

    /// `continuous` keeps the corner style a call site drew before (`.rect(cornerRadius:)` and `style: .continuous`).
    static func panelShape(_ theme: AppTheme, radius: CGFloat, continuous: Bool = false) -> AnyShape {
        theme == .norse
            ? AnyShape(PanelNotchedRectangle())
            : AnyShape(RoundedRectangle(cornerRadius: min(radius, theme.cardRadius), style: continuous ? .continuous : .circular))
    }

    static func controlShape(_ theme: AppTheme, radius: CGFloat? = nil) -> AnyShape {
        switch controlKind(theme, radius: radius) {
        case .notched(let chamfer): return AnyShape(NotchedRectangle(chamfer: chamfer))
        case .rounded(let r): return AnyShape(RoundedRectangle(cornerRadius: r))
        }
    }
}

/// D-220 (L19, E4 reuses it): the shape of a pill (a chip, a role word, a count). Aurora a capsule; Norse notched at
/// `pillChamfer` (the founder's rule "anything rectangular follows the notch", at the button's proportion: art.py section
/// 13); Vardr Labs square. ONE decision: every pill asks for it.
enum PillKind: Equatable {
    case capsule
    case notched(CGFloat)
    case square
}

/// How a pill is drawn: Aurora and Norse fill it; Vardr Labs outlines it (LB 269), as `ButtonLook` does a button.
enum PillLook: Equatable {
    case filled, outlined

    static func of(_ theme: AppTheme) -> PillLook { theme == .vardr ? .outlined : .filled }
}

/// The six kinds of pill SKIPTI draws (the sites are listed in the D-220 plan).
enum PillTone: CaseIterable {
    case accent, owner, selected, unselected, hero, photo
}

/// A colour a pill names: a token, the amber group tone's letter or fill, or a literal.
enum PillInk: Equatable {
    case token(String)
    case amberLetter
    case amberFill
    case white
    case black

    /// The colour's hex in a token set.
    func hex(_ set: ThemeSet) -> String {
        switch self {
        case .token(let name): return Tokens.hex(Tokens.palette[name]!, set)
        case .amberLetter: return Tokens.hex(GroupTone.amber.column.letter, set)
        case .amberFill: return Tokens.hex(GroupTone.amber.column.fill, set)
        case .white: return "#FFFFFF"
        case .black: return "#000000"
        }
    }

    /// The colour, resolved per trait like every token.
    var color: Color {
        switch self {
        case .token(let name): return Tokens.dynamic(Tokens.palette[name]!)
        case .amberLetter: return GroupTone.amber.letter
        case .amberFill: return GroupTone.amber.fill
        case .white: return .white
        case .black: return .black
        }
    }
}

/// What a pill draws, as data (a pure function of tone, look and ground): its words, an optional fill, an optional
/// border drawn inside the shape (1 pt, `PillPaint.borderWidth`). The table is the D-220 plan's Global Constraints.
struct PillPaint: Equatable {
    struct Layer: Equatable {
        let ink: PillInk
        let alpha: Double
        init(_ ink: PillInk, _ alpha: Double = 1) { self.ink = ink; self.alpha = alpha }
    }

    let words: PillInk
    let fill: Layer?
    let border: Layer?

    static let borderWidth: CGFloat = 1

    /// `onPage`: the pill sits straight on the page ground (a filter chip, the currency chip). An outlined pill there
    /// fills with `bg` so the grid's dots do not show under its words (art.py section 13); filled looks ignore it.
    static func of(_ tone: PillTone, look: PillLook, onPage: Bool = false) -> PillPaint {
        let outline = Double(ThemeArt.m("pillOutline"))
        let page: Layer? = onPage ? Layer(.token("bg")) : nil
        let accent = PillInk.token("accent")
        switch (tone, look) {
        case (.photo, _):
            return PillPaint(words: .white, fill: Layer(.black, PhotoCard.chipAlpha), border: nil)
        case (.accent, .filled):
            return PillPaint(words: accent, fill: Layer(.token("accentSoft")), border: nil)
        case (.accent, .outlined):
            return PillPaint(words: accent, fill: page, border: Layer(accent, outline))
        case (.owner, .filled):
            return PillPaint(words: .amberLetter, fill: Layer(.amberFill), border: nil)
        case (.owner, .outlined):
            return PillPaint(words: .amberLetter, fill: page, border: Layer(.amberLetter, outline))
        case (.selected, .filled):
            return PillPaint(words: .token("onAccent"), fill: Layer(accent), border: nil)
        case (.selected, .outlined):
            return PillPaint(words: accent, fill: page, border: Layer(accent))
        case (.unselected, .filled):
            return PillPaint(words: .token("text"), fill: Layer(.token("surface"), 0.8), border: Layer(.token("hairline")))
        case (.unselected, .outlined):
            return PillPaint(words: .token("text"), fill: page, border: Layer(.token("hairline")))
        case (.hero, .filled):
            return PillPaint(words: .token("onHero"), fill: Layer(.token("onHero"), Aurora.heroChipOpacity), border: nil)
        case (.hero, .outlined):
            return PillPaint(words: .token("onHero"), fill: nil, border: Layer(.token("onHero"), outline))
        }
    }
}

/// D-225 (L-D217-9, .drafts/d225/art.py section 42): what the drawn switch paints, as token names (a pure function of
/// its state; one golden table in both shells' tests). ON: an accent track, a surface thumb, no border. OFF: a ringTrack
/// track inside a `switchBorder` border in textFaint, a textDim thumb (Material's way of marking off: each state holds
/// 3:1 without colour alone). Android's twin is `SwitchPaint` in ThemeArt.kt.
struct SwitchPaint: Equatable {
    let track: String
    let border: String?
    let thumb: String

    static func of(on: Bool) -> SwitchPaint {
        on ? SwitchPaint(track: "accent", border: nil, thumb: "surface")
            : SwitchPaint(track: "ringTrack", border: "textFaint", thumb: "textDim")
    }
}

/// D-225: where the switch's thumb sits, from the art values: `switchInset` plus half the thumb from the leading edge
/// when off (15.5), the same from the trailing edge of the `switchWidth` track when on (35.5).
enum SwitchGeometry {
    static func thumbCentreX(on: Bool) -> CGFloat {
        let half = ThemeArt.m("switchInset") + ThemeArt.m("switchThumb") / 2
        return on ? ThemeArt.m("switchWidth") - half : half
    }
}

/// D-220 (L22): a meter (the category bar, E4's settled meter): a capsule in Aurora, square in Norse and Vardr Labs.
enum MeterKind: Equatable {
    case capsule, square
}

/// D-220 (L21): the shape of a card, as a kind (the drawn shape is `ThemeArt.cardShape`).
enum CardShapeKind: Equatable {
    case rounded(CGFloat)
    case notched(CGFloat)
    case square
}

extension ThemeArt {
    static func pillKind(_ theme: AppTheme) -> PillKind {
        switch theme {
        case .aurora: return .capsule
        case .norse: return .notched(m("pillChamfer"))
        case .vardr: return .square
        }
    }

    static func pillShape(_ theme: AppTheme) -> AnyShape {
        switch pillKind(theme) {
        case .capsule: return AnyShape(Capsule())
        case .notched(let chamfer): return AnyShape(NotchedRectangle(chamfer: chamfer))
        case .square: return AnyShape(Rectangle())
        }
    }

    static func meterKind(_ theme: AppTheme) -> MeterKind { theme == .aurora ? .capsule : .square }

    static func meterShape(_ theme: AppTheme) -> AnyShape {
        meterKind(theme) == .capsule ? AnyShape(Capsule()) : AnyShape(Rectangle())
    }

    /// The legend swatch's corner: 2 in Aurora, square elsewhere.
    static func swatchRadius(_ theme: AppTheme) -> CGFloat { theme == .aurora ? 2 : 0 }

    static func swatchShape(_ theme: AppTheme) -> AnyShape { AnyShape(RoundedRectangle(cornerRadius: swatchRadius(theme))) }

    /// A card: Aurora's glass is a rounded rectangle at `GlassCard.resolvedRadius` (an asked radius kept), Norse's is
    /// notched at `chamfer`, Vardr Labs' is square.
    static func cardKind(_ theme: AppTheme, radius: CGFloat? = nil) -> CardShapeKind {
        switch CardStyle.of(theme) {
        case .glass: return .rounded(GlassCard.resolvedRadius(radius, theme: theme))
        case .notched: return .notched(m("chamfer"))
        case .framed: return .square
        }
    }

    static func cardShape(_ theme: AppTheme, radius: CGFloat? = nil) -> AnyShape {
        switch cardKind(theme, radius: radius) {
        case .rounded(let r): return AnyShape(RoundedRectangle(cornerRadius: r, style: .continuous))
        case .notched(let chamfer): return AnyShape(NotchedRectangle(chamfer: chamfer))
        case .square: return AnyShape(Rectangle())
        }
    }
}

/// D-220 (L19): a pill drawn by the theme: its words' colour, its fill and its border (inside the shape) from
/// `PillPaint`, its shape from `ThemeArt.pillShape`. The call site keeps its font and padding (padding before this).
struct ThemePill: ViewModifier {
    let tone: PillTone
    var onPage = false

    func body(content: Content) -> some View {
        let theme = AppearanceState.shared.theme
        let paint = PillPaint.of(tone, look: PillLook.of(theme), onPage: onPage)
        let shape = ThemeArt.pillShape(theme)
        content
            .foregroundStyle(paint.words.color)
            .background {
                if let fill = paint.fill { shape.fill(fill.ink.color.opacity(fill.alpha)) }
            }
            .overlay {
                // Inside the shape: a stroke twice the width, clipped to the shape (an `AnyShape` cannot inset).
                if let border = paint.border {
                    shape.stroke(border.ink.color.opacity(border.alpha), lineWidth: PillPaint.borderWidth * 2)
                        .clipShape(shape)
                        .allowsHitTesting(false)
                }
            }
    }
}

/// D-220 (L21): a photo takes its card's frame: clipped to `ThemeArt.cardShape` and edged as the theme edges a card
/// (Norse's 1 pt edge on the notch, Vardr Labs' frame marks in white at `frameAlpha`: the photo's own words are white,
/// and `textDim` marks vanish on a dark photo; Aurora none).
struct PhotoFrame: ViewModifier {
    let radius: CGFloat?

    func body(content: Content) -> some View {
        let theme = AppearanceState.shared.theme
        content
            .clipShape(ThemeArt.cardShape(theme, radius: radius))
            .overlay {
                switch CardStyle.of(theme) {
                case .glass:
                    Color.clear.allowsHitTesting(false)
                case .notched:
                    // D-226 (L35): the card's edge, on the four straight sides inside the notch (GlassCard's).
                    NotchEdges(chamfer: ThemeArt.m("chamfer")).stroke(ThemeArt.norseEdge, lineWidth: 1)
                        .clipShape(ThemeArt.cardShape(theme, radius: radius))
                        .allowsHitTesting(false)
                case .framed:
                    FrameMarks(inset: ThemeArt.m("frameInset"), length: ThemeArt.m("frameLength"))
                        .stroke(Color.white.opacity(Double(ThemeArt.m("frameAlpha"))), lineWidth: 1)
                        .allowsHitTesting(false)
                }
            }
    }
}

extension View {
    /// D-220 (L19): a pill in the theme's shape and paint; `onPage` when it sits straight on the page ground.
    func themePill(_ tone: PillTone, onPage: Bool = false) -> some View { modifier(ThemePill(tone: tone, onPage: onPage)) }

    /// D-220 (L21): a photo in its card's frame; `radius` as `glassCard`'s (Aurora keeps an asked radius).
    func photoFrame(radius: CGFloat?) -> some View { modifier(PhotoFrame(radius: radius)) }
}

/// G1 fix 1: Norse's notched rectangle at the chamfer `ThemeArt.panelChamfer` gives for its own height.
struct PanelNotchedRectangle: Shape {
    func path(in rect: CGRect) -> Path {
        ArtGeometry.roundedPolygon(ArtGeometry.notched(rect, chamfer: ThemeArt.panelChamfer(height: rect.height)), radius: 0)
    }
}

struct Diamond: Shape {
    var radius: CGFloat = 0
    func path(in rect: CGRect) -> Path { ArtGeometry.roundedPolygon(ArtGeometry.diamond(rect), radius: radius) }
}

struct Hexagon: Shape {
    func path(in rect: CGRect) -> Path { ArtGeometry.roundedPolygon(ArtGeometry.hexagon(rect), radius: 0) }
}

struct FrameMarks: Shape {
    var inset: CGFloat
    var length: CGFloat
    func path(in rect: CGRect) -> Path {
        var path = Path()
        for (a, b) in ArtGeometry.frameMarks(rect, inset: inset, length: length) {
            path.move(to: a)
            path.addLine(to: b)
        }
        return path
    }
}

// MARK: - Per-theme decisions (the spec's section 1 table)

enum TabIndicator: Equatable {
    case pill, underline, outline

    static func of(_ theme: AppTheme) -> TabIndicator {
        switch theme {
        case .aurora: return .pill
        case .norse: return .underline
        case .vardr: return .outline
        }
    }

    /// Norse draws runes for the tab icons; the others Lucide.
    static func drawsRunes(_ theme: AppTheme) -> Bool { theme == .norse }

    /// D-219 (L13): the active tab's glyph colour. Aurora's pill is filled accent, so its glyph is `onAccent`; Norse's
    /// underline leaves the glyph in the gold accent; Vardr Labs' is the ring colour (cyan). The old `glyphUsesRing` is gone.
    static func activeGlyph(_ theme: AppTheme) -> TabGlyphTint {
        switch theme {
        case .aurora: return .onAccent
        case .norse: return .accent
        case .vardr: return .ring
        }
    }

    /// The outline's alpha: the Look Book's 0.50, nudged to 0.75 in Vardr Labs light (art.py: 3:1 on the bar).
    static func outlineAlpha(_ set: ThemeSet) -> Double {
        Double(ThemeArt.m(set == .vardrLight ? "outlineLight" : "outlineDark"))
    }
}

/// D-219 (L13): which token the active tab's glyph is drawn in (the shells map it to `onAccent`, `accent`, `ring`).
enum TabGlyphTint: Equatable {
    case onAccent, accent, ring
}

/// D-219 (L15): the tab bar's ground. Aurora's is glass (a film over blur on iOS); Norse and Vardr Labs are the solid
/// `surface` token (the Look Book's `--p-solid` equals it, measured in art.py section 11). A 1 pt `hairline` rule runs
/// along the bar's top in every theme.
enum TabBarGround: Equatable {
    case glass, solid

    static func of(_ theme: AppTheme) -> TabBarGround { theme == .aurora ? .glass : .solid }

    /// iOS's glass film: `surface` at this alpha over `.ultraThinMaterial` (Android's film is 0.94, no blur: its own constant).
    static let filmAlpha: Double = 0.72
}

/// D-220 (L-D217-3): the navigation bars' ground, the tab bar's decision (`TabBarGround.of`). Norse and Vardr Labs: an
/// opaque `surface` with a 1 point `hairline` line under it (the tab bar's rule; Android's 1 dp line). Aurora: the
/// system's own background (iOS 26 and later: its glass and scroll-edge effect; earlier: its material). The scroll edge
/// is clear in every theme, so the top of a page reads as the art, as before. Each call makes a fresh appearance, so a
/// copy made for one theme (iOS 17 to 25's back button, BarTint.applyBackButton) never carries into the next. The text
/// (titles, bar-button font) goes into the appearance itself: a bar that carries appearances draws its text from them.
enum BarGround {
    typealias Attributes = [NSAttributedString.Key: Any]

    static func standard(_ theme: AppTheme, title: Attributes = [:], large: Attributes = [:], buttons: Attributes = [:]) -> UINavigationBarAppearance {
        let appearance = UINavigationBarAppearance()
        switch TabBarGround.of(theme) {
        case .glass:
            appearance.configureWithDefaultBackground()
        case .solid:
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = Tokens.uiColor("surface")
            appearance.shadowColor = Tokens.uiColor("hairline")
            appearance.shadowImage = line
        }
        return text(appearance, title: title, large: large, buttons: buttons)
    }

    /// The scroll edge is clear in every theme, so it takes no theme (the top of a page reads as its art).
    static func scrollEdge(title: Attributes = [:], large: Attributes = [:], buttons: Attributes = [:]) -> UINavigationBarAppearance {
        let appearance = UINavigationBarAppearance()
        appearance.configureWithTransparentBackground()
        return text(appearance, title: title, large: large, buttons: buttons)
    }

    /// The solid bars' line: 1 point, a template, so `shadowColor` draws it (UIKit's own line is one pixel).
    static var line: UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        }.withRenderingMode(.alwaysTemplate)
    }

    /// [appearance] with the theme's title attributes and, in every button state, the bar-button font (D-215: iOS 17
    /// to 25 ignore the legacy UIBarButtonItem proxy on a bar that carries appearances). Empty attributes leave
    /// UIKit's own. Returns [appearance] itself.
    @discardableResult
    static func text(_ appearance: UINavigationBarAppearance, title: Attributes, large: Attributes, buttons: Attributes) -> UINavigationBarAppearance {
        if !title.isEmpty { appearance.titleTextAttributes = title }
        if !large.isEmpty { appearance.largeTitleTextAttributes = large }
        if let font = buttons[.font] { buttonFont(font, on: appearance) }
        return appearance
    }

    /// D-215: the bar-button font on all three button appearances of [copy], in every state (one loop for both callers).
    static func buttonFont(_ font: Any, on copy: UINavigationBarAppearance) {
        for button in [copy.buttonAppearance, copy.doneButtonAppearance, copy.backButtonAppearance] {
            for look in [button.normal, button.highlighted, button.disabled, button.focused] {
                look.titleTextAttributes[.font] = font
            }
        }
    }
}

/// D-219 (L17): the raised +'s footprint. Every face is laid out in one `footprint` x `footprint` box and drawn centred on
/// it (the diamond overflows), so every bar is 70 tall in every theme (6 + 64) and every face's centre sits 50 above the
/// row's bottom (`footprint / 2 + lift`). `faceSize` is each face's own box.
enum AddButtonGeometry {
    static let footprint: CGFloat = 64
    static let lift: CGFloat = 18

    static func faceSize(_ art: AddButtonArt) -> CGSize {
        switch art {
        case .circle: return CGSize(width: footprint, height: footprint)
        case .diamond:
            let box = ThemeArt.m("diamondSide") * sqrt(2)
            return CGSize(width: box, height: box)
        case .bolt: return CGSize(width: ThemeArt.m("boltWidth"), height: ThemeArt.m("boltHeight"))
        }
    }
}

/// D-219 (L16): the + circle's shadow is the diamond's neutral one (the Look Book's `0 6px 14px rgba(0,0,0,.2)` as D-213
/// converted it): black at `alpha`, `radius` 7, `y` 6. The bolt keeps none. Android's twin is elevation 7 dp.
enum AddButtonShadow {
    static let alpha: Double = 0.2
    static let radius: CGFloat = 7
    static let y: CGFloat = 6
}

/// D-219 (L14): the Vardr Labs bolt's glyph, SKIPTI's own drawing from the Look Book (not a Lucide icon): four arms
/// stroked at `LucideStroke.width`, round caps and joins, and a filled centre dot of radius 1.3 written as an arc path
/// so both shells draw it from the same two strings. 24-unit viewport. Android's twin is `ReticleGlyph` in ThemeArt.kt.
/// As a view it takes the current foreground style, scaled from the 24-unit viewport to `size` like `LucideIcon`.
struct ReticleGlyph: View {
    static let arms = "M3.5 12h5.5M15 12h5.5M12 3.5V9M12 15v5.5"
    static let dot = "M10.7 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0"

    let size: CGFloat

    var body: some View {
        ZStack {
            ReticleShape(d: Self.arms)
                .stroke(style: StrokeStyle(lineWidth: LucideStroke.width * size / 24, lineCap: .round, lineJoin: .round))
            ReticleShape(d: Self.dot).fill()
        }
        .frame(width: size, height: size)
    }
}

/// One of the reticle's path strings, scaled from the 24-unit viewport to the rect (as `LucideShape` does).
private struct ReticleShape: Shape {
    let d: String

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / 24
        return ((try? SVGPath.parse(d)) ?? Path())
            .applying(CGAffineTransform(scaleX: scale, y: scale))
            .offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

enum AddButtonArt: Equatable {
    case circle, diamond, bolt

    static func of(_ theme: AppTheme) -> AddButtonArt {
        switch theme {
        case .aurora: return .circle
        case .norse: return .diamond
        case .vardr: return .bolt
        }
    }
}

enum CardStyle: Equatable {
    case glass, notched, framed

    static func of(_ theme: AppTheme) -> CardStyle {
        switch theme {
        case .aurora: return .glass
        case .norse: return .notched
        case .vardr: return .framed
        }
    }
}

/// A group's tile (`person: false`) or a person's initial on a tone (`person: true`).
enum TileShape: Equatable {
    case rounded, circle, diamond, square

    static func of(_ theme: AppTheme, person: Bool) -> TileShape {
        switch theme {
        case .aurora: return person ? .circle : .rounded
        case .norse: return .diamond
        case .vardr: return .square
        }
    }
}

enum ButtonLook: Equatable {
    case filled, outlined

    /// A disabled button is drawn at this opacity on both phones (D-220; the 0.45 every style carried inline).
    static let disabledAlpha: Double = 0.45

    static func of(_ theme: AppTheme) -> ButtonLook { theme == .vardr ? .outlined : .filled }

    /// The case the button's words take: Vardr Labs' outlined look uppercases them, unless the label is an
    /// identifier that must keep its case (an @username suggestion: handles are lowercase).
    static func labelCase(_ theme: AppTheme, keepsCase: Bool) -> Text.Case? {
        of(theme) == .outlined && !keepsCase ? .uppercase : nil
    }

    /// The outlined words are tracked .08 em of the size they draw at.
    static func tracking(_ size: CGFloat) -> CGFloat {
        TypeRole.scaledSize(size, relativeTo: .body, category: AppearanceState.shared.contentSizeCategory) * 0.08
    }
}

enum Ground: Equatable {
    case curtains, runes, grid

    /// D-214: Aurora's curtains and Norse's runes move (ThemeMotion.swift); Vardr Labs' grid stays still.
    static func of(_ theme: AppTheme) -> Ground {
        switch theme {
        case .aurora: return .curtains
        case .norse: return .runes
        case .vardr: return .grid
        }
    }
}

enum SectionMark {
    /// What goes before a section label: ᛟ and a space in Norse, nothing elsewhere.
    static func prefix(_ theme: AppTheme) -> String? { theme == .norse ? Runes.sectionMark + " " : nil }
}

/// D-221 (Q2 a, K05, K06): an avatar's circle. Soft: `accentSoft` with the initials, emoji or glyph in `accent` (every
/// avatar but one); accent: `accent` with `onAccent` (Home's account badge). A photo covers the fill. art.py 20.
enum AvatarFill: Equatable {
    case soft, accent

    /// The token names of the circle's ground and of what is drawn on it.
    var groundToken: String { self == .soft ? "accentSoft" : "accent" }
    var inkToken: String { self == .soft ? "accent" : "onAccent" }
    var ground: Color { Color(Tokens.uiColor(groundToken)) }
    var ink: Color { Color(Tokens.uiColor(inkToken)) }
}

enum AvatarStyle {
    /// D-221: Home's account badge takes the accent fill; every other avatar the soft one.
    static func fill(badge: Bool) -> AvatarFill { badge ? .accent : .soft }

    /// D-221: Norse edges an avatar in its gold-brown, 1 pt; the others draw no edge (the hairline goes).
    static func edgeHex(_ theme: AppTheme) -> String? { theme == .norse ? ThemeArt.hex["norseEdge"] : nil }
}

/// D-221 (L44): a milestone's 44 pt seal takes the theme's tile clip: Aurora a circle, Norse C2's diamond with the
/// gold-brown edge, Vardr Labs a square (drawn through `TileClip`).
enum SealStyle {
    static func clip(_ theme: AppTheme) -> TileShape {
        switch theme {
        case .aurora: return .circle
        case .norse: return .diamond
        case .vardr: return .square
        }
    }
}

/// D-221 (L40): You's progress pill, "6 of 7 done". Vardr Labs alone leads it with a 7 pt dot in `ring`, gap 7
/// (`progressDot`, `progressDotGap`). D-221 Task 4 makes it the view (`extension ProgressPill: View`).
struct ProgressPill {
    let done: Int
    let total: Int

    static func showsDot(_ theme: AppTheme) -> Bool { theme == .vardr }
}

/// D-213: the meta separator. Norse joins with ᛫, the others with the middle
/// dot. Notifications and exported files keep the middle dot (Push.swift).
enum Separator {
    static var meta: String { meta(for: AppearanceState.shared.theme) }

    static func meta(for theme: AppTheme) -> String { theme == .norse ? " \(Runes.separator) " : " \u{00B7} " }

    /// A meta line whose separators take the accent in Norse; plain elsewhere. D-215: in Norse every separator
    /// rune (also one inside a part, as in a category label) is drawn in the rune font at [size], the size of the
    /// line's own text, scaled with it.
    static func attributed(_ parts: [String], size: CGFloat, theme: AppTheme = AppearanceState.shared.theme) -> AttributedString {
        var out = AttributedString()
        for (i, part) in parts.enumerated() {
            if i > 0 {
                var sep = AttributedString(meta(for: theme))
                if theme == .norse { sep.foregroundColor = Tokens.accent }
                out += sep
            }
            out += AttributedString(part)
        }
        if theme == .norse {
            let font = TypeRole.rune(size, relativeTo: .body)
            var from = out.startIndex
            while let found = out[from...].range(of: Runes.separator) {
                out[found].font = font
                from = found.upperBound
            }
        }
        return out
    }

    /// A meta line drawn by a body; [size] is the size of the font the caller gives it.
    static func text(_ parts: [String], size: CGFloat) -> Text { Text(attributed(parts, size: size)) }
}

// MARK: - D-213 views the shared components draw

/// The clip a tile takes for a `TileShape`.
enum TileClip {
    static func shape(_ shape: TileShape, size: CGFloat) -> AnyShape {
        switch shape {
        case .rounded: return AnyShape(RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
        case .circle: return AnyShape(Circle())
        case .diamond: return AnyShape(Diamond(radius: ThemeArt.m("tileRadius")))
        case .square: return AnyShape(Rectangle())
        }
    }

    /// D-220 (L23): a diamond is drawn at `size * sqrt(2)`, centred on the tile's `size` footprint (it overflows by
    /// 0.207 x size a side; art.py section 15), so its side is the tile's, as the Look Book draws it. Every other shape
    /// is drawn at `size`.
    static func drawnSide(_ shape: TileShape, size: CGFloat) -> CGFloat {
        shape == .diamond ? size * 2.0.squareRoot() : size
    }

    /// D-225 fix wave (look F3): the size that draws a tile of this shape inside a square slot of `slot`: the slot
    /// itself, or for Norse's diamond the size whose drawn side (`drawnSide`) is the slot, so the diamond's tips stay
    /// in it and the gap to what follows (Group settings' chevron) is Aurora's.
    static func sizeInSlot(_ shape: TileShape, slot: CGFloat) -> CGFloat {
        slot * slot / drawnSide(shape, size: slot)
    }

    /// The initial's face on a tile: the display face at 700 in every theme (D-217: the Look Book's `.tile`; Norse's
    /// is Cormorant SC through the display face). D-220: one factor, 0.42, on every shape: the diamond's inscribed
    /// square is now 0.707 of the size, as large as the other shapes' letter needs.
    static func initialFont(_ shape: TileShape, size: CGFloat) -> Font {
        TypeRole.display(size * 0.42, weight: 700)
    }

    /// Norse's diamond carries the gold-brown edge; the other shapes none.
    @ViewBuilder
    static func edge(_ shape: TileShape) -> some View {
        if shape == .diamond { Diamond(radius: ThemeArt.m("tileRadius")).stroke(ThemeArt.norseEdge, lineWidth: 1) }
    }
}

extension View {
    /// D-221 (S5): lays a tile out by its drawn side (`TileClip.drawnSide`), so Norse's diamond, drawn at size x sqrt 2,
    /// keeps its tips inside the page margin and its spacing from the next view; every other shape is laid out by its
    /// size, as before. One decision for the group header's tile and the milestone's seal.
    func tileFootprint(_ shape: TileShape, size: CGFloat) -> some View {
        let side = TileClip.drawnSide(shape, size: size)
        return frame(width: side, height: side)
    }
}

/// Vardr Labs' ground: a still dot grid (accent at 0.45 in a layer at 0.45; art.py measures text over it).
struct VardrGrid: View {
    var body: some View {
        Canvas { context, size in
            let r = ThemeArt.m("gridDot")
            var path = Path()
            for d in ArtGeometry.gridDots(size, pitch: ThemeArt.m("gridPitch")) {
                path.addEllipse(in: CGRect(x: d.x - r, y: d.y - r, width: r * 2, height: r * 2))
            }
            context.fill(path, with: .color(Tokens.accent.opacity(Double(ThemeArt.m("gridAlpha")))))
        }
        .accessibilityHidden(true)
    }
}

/// The raised +'s face per theme: Aurora's accent circle, Norse's gold diamond, Vardr Labs' hex bolt (it turns, D-214).
/// D-219 (L14, L16, L17): every face is laid out in one footprint box and drawn centred on it (the diamond overflows),
/// the circle and the diamond share the neutral shadow, and the bolt carries the reticle where the others carry the
/// plus. The glyph is the set's `onAccent` on all three (art.py section 7).
struct AddButtonFace: View {
    let art: AddButtonArt
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        face
            .frame(width: AddButtonGeometry.footprint, height: AddButtonGeometry.footprint)
    }

    @ViewBuilder
    private var face: some View {
        switch art {
        case .circle:
            let size = AddButtonGeometry.faceSize(.circle)
            plusGlyph
                .frame(width: size.width, height: size.height)
                .background(Tokens.accent, in: Circle())
                .overlay(Circle().strokeBorder(Tokens.bg, lineWidth: 4))
                .shadow(color: Color.black.opacity(AddButtonShadow.alpha), radius: AddButtonShadow.radius, y: AddButtonShadow.y)
        case .diamond:
            let size = AddButtonGeometry.faceSize(.diamond)
            let shape = Diamond(radius: ThemeArt.m("diamondRadius"))
            plusGlyph
                .frame(width: size.width, height: size.height)
                .background(shape.fill(LinearGradient(colors: [ThemeArt.diamondTop, ThemeArt.diamondBottom],
                                                      startPoint: .top, endPoint: .bottom)))
                .overlay(shape.stroke(Tokens.bg, lineWidth: 4))
                .contentShape(shape)
                .shadow(color: Color.black.opacity(AddButtonShadow.alpha), radius: AddButtonShadow.radius, y: AddButtonShadow.y)
        case .bolt:
            let size = AddButtonGeometry.faceSize(.bolt)
            let dark = scheme == .dark
            ReticleGlyph(size: ThemeArt.m("reticleGlyph"))
                .foregroundStyle(Tokens.onAccent)
                .frame(width: size.width, height: size.height)
                .background(Hexagon().fill(LinearGradient(
                    stops: ThemeArt.boltStops(dark: dark),
                    startPoint: UnitPoint(x: ThemeArt.m("boltStartX"), y: ThemeArt.m("boltStartY")),
                    endPoint: UnitPoint(x: ThemeArt.m("boltEndX"), y: ThemeArt.m("boltEndY")))))
                .overlay(Circle().stroke(Color.white.opacity(ThemeArt.reticleAlpha(dark: dark)), lineWidth: 1)
                    .frame(width: size.height * 2 / 3, height: size.height * 2 / 3))
                .contentShape(Hexagon())
        }
    }

    private var plusGlyph: some View {
        LucideIcon(glyph: .plus, size: 28, stroke: LucideStroke.plus).foregroundStyle(Tokens.onAccent)
    }
}

// MARK: - D-213 words: the section mark, the ornament, the titles

/// D-213: ᛟ before a section label in Norse, in the accent and hidden from VoiceOver (the label is read alone).
struct SectionLabelMark: ViewModifier {
    let size: CGFloat

    func body(content: Content) -> some View {
        if let prefix = SectionMark.prefix(AppearanceState.shared.theme) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(verbatim: prefix).font(TypeRole.rune(size, relativeTo: .footnote)).foregroundStyle(Tokens.accent).accessibilityHidden(true)
                content
            }
        } else {
            content
        }
    }
}

/// D-213 (the founder, demo option A): a gold rule, ᛜ, a silver rule, under the title of the four tab roots, in
/// Norse only (D-217: not on You, which has no large title). Decorative: hidden from VoiceOver.
/// D-217 (the Look Book's `.orn`): each rule is 42% of the width and the rune sits in the 16% between them; the
/// ornament hangs `gapUnderTitle` points under the large title.
struct TitleOrnament: View {
    static func shows(_ theme: AppTheme) -> Bool { theme == .norse }

    /// One rule's share of the width; the rune takes the rest (16%).
    static let ruleFraction: CGFloat = 0.42
    /// Points between the large title's text and the ornament's rules.
    static let gapUnderTitle: CGFloat = 8
    /// The row's height: it holds the 17 point rune (D-226, art.py section 46: Vardr Runes Carved's 1.15 em line box,
    /// 19.55, rounded up to even; the same rule gave 16 for the 13 point rune).
    static let rowHeight: CGFloat = 20
    /// Measured on iOS 27 (iPhone 17): from the large title's text to the top of a tab root's scroll content, before
    /// the screen's own padding.
    static let titleBottomInset: CGFloat = 15

    static func ruleWidth(in width: CGFloat) -> CGFloat { width * ruleFraction }

    /// The top offset that puts the row `gapUnderTitle` under the title, for a screen whose content already has
    /// [above] points of its own padding over the ornament.
    static func topOffset(above: CGFloat) -> CGFloat { gapUnderTitle - titleBottomInset - above }

    /// Points of the screen's own padding above the ornament (the root's content inset).
    var above: CGFloat = 16

    var body: some View {
        if Self.shows(AppearanceState.shared.theme) {
            GeometryReader { geo in
                HStack(spacing: 0) {
                    Rectangle().fill(Tokens.accent).frame(width: Self.ruleWidth(in: geo.size.width), height: 1)
                    Spacer(minLength: 0)
                    Text(verbatim: Runes.ornament).font(TypeRole.rune(ThemeArt.m("ornamentRune"))).foregroundStyle(Tokens.accent)
                    Spacer(minLength: 0)
                    Rectangle().fill(ThemeArt.norseSilver).frame(width: Self.ruleWidth(in: geo.size.width), height: 1)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .frame(height: Self.rowHeight)
            .padding(.top, Self.topOffset(above: above))
            .accessibilityHidden(true)
        }
    }
}

/// D-214 (F1, the founder's floor, 2026-10-15): a bar item sits over the page art at rest, so it takes `groundAccent`
/// (the accent in every set but Aurora light, where the accent reaches 4.00:1 on the stacked art in the bar band and the
/// label colour 4.98:1, .drafts/d214/stack-bar.out). A SwiftUI toolbar item takes the tint of the place its `.toolbar`
/// is declared (measured on iOS 27: a tint on the NavigationStack or on the screen reaches it, the window's and the
/// bar's do not), so every ToolbarItem's control carries `.barTint()`. UIKit's back button on iOS 17 to 25 would take
/// SwiftUI's environment tint (the accent), so there it is set independently of any tint: the back button's title
/// colour and an always-original chevron in the bar colour, on copies of the bar's own appearances (TitleStyle.apply;
/// iOS 26 and later draw the chevron in the label colour in its glass, measured). Cards, toggles and pickers keep
/// `accent`. Profile's Done is white on its own black bar and keeps that.
enum BarTint {
    static let token = "groundAccent"
    static var color: UIColor { Tokens.uiColor(token) }

    /// D-217: points per Lucide unit for the back chevron: its ink is 12 units tall, so 15 points, the height of the
    /// 17 point semibold SF chevron it replaces.
    static let backChevronScale: CGFloat = 1.25

    /// The back indicator: Lucide's chevron-left (D-217; Android's back arrow takes the same drawing) drawn in
    /// [color] as an original image, so no tint recolours it; the mask is the same drawing in black.
    static func backIndicator(_ color: UIColor) -> (image: UIImage, mask: UIImage) {
        let image = LucideGlyph.chevronLeft.inkImage(color: color, pointsPerUnit: backChevronScale)
        let mask = LucideGlyph.chevronLeft.inkImage(color: .black, pointsPerUnit: backChevronScale)
        return (image.withRenderingMode(.alwaysOriginal), mask)
    }

    /// A copy of [appearance] whose back button (title and chevron) is [color], with the theme's title attributes;
    /// its background and shadow are the original's (a copy, never a `configureWith...`).
    static func backButton(_ appearance: UINavigationBarAppearance, color: UIColor, title: [NSAttributedString.Key: Any],
                           large: [NSAttributedString.Key: Any], buttons: [NSAttributedString.Key: Any]) -> UINavigationBarAppearance {
        let copy = appearance.copy()
        copy.titleTextAttributes = title
        copy.largeTitleTextAttributes = large
        // D-215: iOS 17 to 25 draws a bar's button text from these appearances (the legacy UIBarButtonItem proxy is
        // ignored by a bar that carries them), so the bar-button font goes on all three, in every state.
        if let font = buttons[.font] { BarGround.buttonFont(font, on: copy) }
        copy.backButtonAppearance.normal.titleTextAttributes[.foregroundColor] = color
        copy.backButtonAppearance.highlighted.titleTextAttributes[.foregroundColor] = color
        let indicator = backIndicator(color)
        copy.setBackIndicatorImage(indicator.image, transitionMaskImage: indicator.mask)
        return copy
    }

    /// iOS 17 to 25: every appearance of [bar] (standard, scroll edge, compact, compact scroll edge) gets the back
    /// button in the bar colour. A live bar's own appearances are copied (TitleStyle.apply has just set them to
    /// [theme]'s ground); the appearance proxy (bars made later), which cannot be asked for them, starts from [theme]'s
    /// ground (D-220: BarGround). An unset scroll-edge appearance is UIKit's transparent copy of the standard one, made
    /// explicitly so the bar still reads clear at the top; an unset compact one follows.
    @MainActor
    static func applyBackButton(to bar: UINavigationBar, proxy: Bool, theme: AppTheme, color: UIColor, title: [NSAttributedString.Key: Any],
                                large: [NSAttributedString.Key: Any], buttons: [NSAttributedString.Key: Any]) {
        let standard = proxy ? BarGround.standard(theme) : bar.standardAppearance
        let edge = (proxy ? BarGround.scrollEdge() : bar.scrollEdgeAppearance) ?? {
            let clear = standard.copy()
            clear.configureWithTransparentBackground()
            return clear
        }()
        let compact = (proxy ? nil : bar.compactAppearance) ?? standard
        let compactEdge = (proxy ? nil : bar.compactScrollEdgeAppearance) ?? edge
        bar.standardAppearance = backButton(standard, color: color, title: title, large: large, buttons: buttons)
        bar.scrollEdgeAppearance = backButton(edge, color: color, title: title, large: large, buttons: buttons)
        bar.compactAppearance = backButton(compact, color: color, title: title, large: large, buttons: buttons)
        bar.compactScrollEdgeAppearance = backButton(compactEdge, color: color, title: title, large: large, buttons: buttons)
    }
}

extension View {
    /// A bar item's tint: the ground's accent (BarTint).
    func barTint() -> some View { tint(Tokens.groundAccent) }
}

/// D-213 (the founder, demo option A): every navigation title in the theme's display face, set on the
/// navigation bars themselves, so the system's large title that folds into the bar on scroll is kept.
/// D-248 (U31, decision 186): the room an inline title has on the narrowest iPhone (375 pt) beside the back button. UIKit
/// centres the title, so it keeps clear of the back button's side on both sides: iOS 26 and later draw the back button
/// as a 44 pt glass circle 16 from the edge, and the title keeps 8 more off it. Measured against the cut the founder saw
/// (Norse's "Appearance" at text step 2; D248FixesTests).
enum TitleWidth {
    static let phone: CGFloat = 375
    static let reserve: CGFloat = 16 + 44 + 8
    static let room: CGFloat = phone - 2 * reserve
}

enum TitleStyle {
    /// The Look Book's `.title`: 700 (Vardr Labs 600).
    static func weight(_ theme: AppTheme) -> CGFloat { theme == .vardr ? 600 : 700 }

    /// The Look Book's tracking in em: -0.01; Norse +0.03; Vardr Labs -0.02.
    static func tracking(_ theme: AppTheme) -> CGFloat {
        switch theme {
        case .aurora: return -0.01
        case .norse: return 0.03
        case .vardr: return -0.02
        }
    }

    /// The system's sizes (large 34, inline 17), scaled for SKIPTI's text size. D-248 (U31, decision 186): an INLINE title
    /// stops growing at `inlineCap` (UIKit centres it between the back button and the trailing items and cuts what does not
    /// fit: Norse's "APPEARANCE" at text step 2); large titles are untouched.
    static func font(large: Bool, theme: AppTheme, category: UIContentSizeCategory) -> UIFont {
        let base = TypeRole.base(TypeRole.face(.display, theme: theme), size: large ? TypeScale.largeTitle : TypeScale.inlineTitle, weight: weight(theme), tabular: false)
        let traits = UITraitCollection(preferredContentSizeCategory: category)
        if large {
            return UIFontMetrics(forTextStyle: .largeTitle).scaledFont(for: base, compatibleWith: traits)
        }
        return UIFontMetrics(forTextStyle: .headline).scaledFont(for: base, maximumPointSize: inlineCap(theme), compatibleWith: traits)
    }

    /// D-248 (U31): the inline title's largest size per theme, measured so the longest fixed pushed title fits a 375 pt
    /// iPhone beside the back button at every text step (D248FixesTests' width table; Android's bar is checked at 360 dp).
    /// Measured: the widest is Norse's "Profile and account", 166.9 pt at 17, so 23 draws it 225.8 wide in the 239 of room
    /// (24 would leave 3.4; 23 keeps the slack). One cap for every theme; step 2 on a phone at its default size (21) is
    /// under it, so the cap only bites at the larger steps.
    static func inlineCap(_ theme: AppTheme) -> CGFloat { 23 }

    static func attributes(large: Bool, theme: AppTheme, category: UIContentSizeCategory) -> [NSAttributedString.Key: Any] {
        let f = font(large: large, theme: theme, category: category)
        return [.font: f, .kern: f.pointSize * tracking(theme), .foregroundColor: Tokens.uiColor("text")]
    }

    /// D-215: a bar button (Done, Cancel, a ToolbarItem) is UIKit's own text: the system sets it in the phone's face at
    /// 17, so it gets the theme's body face at 17, scaled like the system's `.body` text. Colour stays the bar's tint.
    static func barButtonAttributes(theme: AppTheme, category: UIContentSizeCategory) -> [NSAttributedString.Key: Any] {
        let base = TypeRole.base(TypeRole.face(.body, theme: theme), size: 17, weight: 400, tabular: false)
        let font = UIFontMetrics(forTextStyle: .body)
            .scaledFont(for: base, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
        return [.font: font]
    }

    private static let barButtonStates: [UIControl.State] = [.normal, .highlighted, .disabled, .focused]


    static func styleBarButton(_ item: UIBarButtonItem, _ attributes: [NSAttributedString.Key: Any]) {
        for controlState in barButtonStates { item.setTitleTextAttributes(attributes, for: controlState) }
    }

    /// D-248 (U31, the look): a bar on screen keeps its title label at the width measured in the last font, so a text
    /// size or theme changed while the page shows (Appearance's own slider) cut its title ("APPEARAN..." in Norse at step 2;
    /// the same page entered again was whole). Setting the title again makes UIKit measure it in the new font.
    /// The look found that setting the title again is not enough (step 3 was still cut): the label and every view up to
    /// the bar are told their size is stale, the bar lays out now, and once more on the next turn (SwiftUI may write the
    /// title after this runs).
    @MainActor
    static func remeasureTitle(of bar: UINavigationBar, again: Bool = true) {
        guard let title = bar.topItem?.title else { return }
        func stale(_ view: UIView) {
            for sub in view.subviews {
                if let label = sub as? UILabel, (label.attributedText?.string ?? label.text) == title {
                    var up: UIView? = label
                    while let v = up, v !== bar {
                        v.invalidateIntrinsicContentSize()
                        v.setNeedsLayout()
                        up = v.superview
                    }
                }
                stale(sub)
            }
        }
        stale(bar)
        bar.setNeedsLayout()
        bar.layoutIfNeeded()
        if again { DispatchQueue.main.async { remeasureTitle(of: bar, again: false) } }
    }

    /// Every bar already on screen, and the appearance proxy for bars made later (a sheet's own stack).
    @MainActor
    static func apply(_ state: AppearanceState, to window: UIWindow) {
        let large = attributes(large: true, theme: state.theme, category: state.contentSizeCategory)
        let inline = attributes(large: false, theme: state.theme, category: state.contentSizeCategory)
        let buttons = barButtonAttributes(theme: state.theme, category: state.contentSizeCategory)
        for controlState in barButtonStates { UIBarButtonItem.appearance().setTitleTextAttributes(buttons, for: controlState) }
        let proxy = UINavigationBar.appearance()
        proxy.largeTitleTextAttributes = large
        proxy.titleTextAttributes = inline
        // D-214 (F1): UIKit's own bar buttons take the bar's tint, the ground's accent; on iOS 17 to 25 the back
        // button is also set independently of any tint (BarTint.applyBackButton), since SwiftUI may tint the bar.
        proxy.tintColor = BarTint.color
        // D-220: the theme's ground (BarGround), fresh, BEFORE iOS 17 to 25's back button copies it, on the proxy and on
        // every live bar; a live bar's compact appearances are cleared so they follow these, never a copy left from the
        // last theme.
        proxy.standardAppearance = BarGround.standard(state.theme, title: inline, large: large, buttons: buttons)
        proxy.scrollEdgeAppearance = BarGround.scrollEdge(title: inline, large: large, buttons: buttons)
        if #unavailable(iOS 26) { BarTint.applyBackButton(to: proxy, proxy: true, theme: state.theme, color: BarTint.color, title: inline, large: large, buttons: buttons) }
        for bar in navigationBars(in: window.rootViewController) {
            bar.standardAppearance = BarGround.standard(state.theme, title: inline, large: large, buttons: buttons)
            bar.scrollEdgeAppearance = BarGround.scrollEdge(title: inline, large: large, buttons: buttons)
            bar.compactAppearance = nil
            bar.compactScrollEdgeAppearance = nil
            bar.largeTitleTextAttributes = large
            bar.titleTextAttributes = inline
            remeasureTitle(of: bar)
            bar.tintColor = BarTint.color
            for item in bar.items ?? [] {
                for button in (item.leftBarButtonItems ?? []) + (item.rightBarButtonItems ?? []) + [item.backBarButtonItem].compactMap({ $0 }) {
                    styleBarButton(button, buttons)
                }
            }
            if #unavailable(iOS 26) { BarTint.applyBackButton(to: bar, proxy: false, theme: state.theme, color: BarTint.color, title: inline, large: large, buttons: buttons) }
        }
    }

    static func navigationBars(in root: UIViewController?) -> [UINavigationBar] {
        guard let root else { return [] }
        var bars: [UINavigationBar] = []
        if let nav = root as? UINavigationController { bars.append(nav.navigationBar) }
        for child in root.children { bars += navigationBars(in: child) }
        if let presented = root.presentedViewController, presented !== root.parent { bars += navigationBars(in: presented) }
        return bars
    }
}
