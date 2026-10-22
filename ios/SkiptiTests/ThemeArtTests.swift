import CoreText
import SwiftUI
import UIKit
import XCTest
@testable import Skipti

/// D-213 (E2, ADR-0041 "Theme art"): the static art's data, geometry and
/// per-theme decisions. Goldens are worked by hand (see each test); the
/// colours and sizes are checked against .drafts/d213/art.py. Android's twin
/// is ThemeArtTest.
final class ThemeArtTests: XCTestCase {
    /// The spec's glyph table, by code point (re-derived from the Look Book's FUTHARK string with Python's
    /// unicodedata: RUNIC LETTER FEHU 16A0 ... OTHALAN 16DF, SINGLE PUNCTUATION 16EB).
    func testTheGlyphTableIsTheSpecs() {
        func cp(_ s: String) -> [UInt32] { s.unicodeScalars.map(\.value) }
        XCTAssertEqual(cp(Runes.balance), [0x16A0])
        XCTAssertEqual(cp(Runes.expenses), [0x16B7])
        XCTAssertEqual(cp(Runes.groups), [0x16D7])
        XCTAssertEqual(cp(Runes.settle), [0x16CF])
        XCTAssertEqual(cp(Runes.separator), [0x16EB])
        XCTAssertEqual(cp(Runes.sectionMark), [0x16DF])
        XCTAssertEqual(cp(Runes.ornament), [0x16DC])
        XCTAssertEqual(Runes.futhark.flatMap(cp), [
            0x16A0, 0x16A2, 0x16A6, 0x16A8, 0x16B1, 0x16B2, 0x16B7, 0x16B9, 0x16BA, 0x16BE, 0x16C1, 0x16C3,
            0x16C7, 0x16C8, 0x16C9, 0x16CA, 0x16CF, 0x16D2, 0x16D6, 0x16D7, 0x16DA, 0x16DC, 0x16DE, 0x16DF,
        ])
        XCTAssertEqual(Runes.all.count, 25)
        XCTAssertEqual(HomeTab.allCases.map(\.rune), [Runes.balance, Runes.expenses, Runes.groups, Runes.settle])
    }

    /// D-215: the bundled file (Vardr Runes Carved Regular) holds every rune SKIPTI draws, opened straight from the
    /// bundle with Core Text rather than through a name that could fall back to the system font.
    private func bundledRuneFont() throws -> CTFont {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "VardrRunes-CarvedRegular", withExtension: "ttf"), "the rune font is not bundled")
        let descriptors = try XCTUnwrap(CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor])
        return CTFontCreateWithFontDescriptor(try XCTUnwrap(descriptors.first), 20, nil)
    }

    private func glyphs(_ s: String, in font: CTFont) -> [CGGlyph]? {
        var chars = Array(s.utf16)
        var out = [CGGlyph](repeating: 0, count: chars.count)
        return CTFontGetGlyphsForCharacters(font, &chars, &out, chars.count) ? out : nil
    }

    /// The face Core Text draws a string's first character from, for a base font: its own, or the fallback it picks.
    private func drawnFrom(_ s: String, base: UIFont) -> String {
        let cf = s as CFString
        let font = CTFontCreateForString(base as CTFont, cf, CFRange(location: 0, length: CFStringGetLength(cf)))
        return CTFontCopyPostScriptName(font) as String
    }

    func testEveryRuneIsInTheBundledRuneFont() throws {
        let font = try bundledRuneFont()
        XCTAssertEqual(CTFontCopyPostScriptName(font) as String, TypeRole.runes)
        for rune in Runes.all {
            let g = try XCTUnwrap(glyphs(rune, in: font), "U+\(String(rune.unicodeScalars.first?.value ?? 0, radix: 16, uppercase: true)) has no glyph")
            XCTAssertFalse(g.contains(0))
        }
        // The name the app asks for resolves to the bundled file, not to a silent system fallback.
        XCTAssertEqual(UIFont(name: TypeRole.runes, size: 12)?.fontName, TypeRole.runes)
    }

    /// The negative: U+16F9 is unassigned (the Runic block ends at U+16F8), so the font has no glyph for it.
    func testTheRuneFontRefusesAnUnassignedCodePoint() throws {
        let font = try bundledRuneFont()
        XCTAssertNil(glyphs("\u{16F9}", in: font))
    }

    /// Every rune, in the rune role (fixed or scaled) and in each theme's text faces, is drawn FROM Vardr Runes
    /// Carved: not from Apple Symbols, the system's fallback for the Runic block (D-213 drew from it).
    func testEveryRuneDrawsFromVardrRunesInEveryRole() {
        let faces: [TypeRole.Face] = [.grotesk, .sans, .cormorant, .plex]
        for rune in Runes.all {
            let name = "U+\(String(rune.unicodeScalars.first?.value ?? 0, radix: 16, uppercase: true))"
            XCTAssertEqual(drawnFrom(rune, base: TypeRole.runeUIFont(22, relativeTo: nil, category: .large)), TypeRole.runes, name)
            XCTAssertEqual(drawnFrom(rune, base: TypeRole.runeUIFont(13, relativeTo: .footnote, category: .accessibilityLarge)), TypeRole.runes, name)
            for face in faces {
                for weight: CGFloat in [400, 600, 700] {
                    let base = TypeRole.base(face, size: 14, weight: weight, tabular: false)
                    XCTAssertEqual(drawnFrom(rune, base: base), TypeRole.runes, "\(name) in \(face) at \(weight)")
                    // D-215 fix wave (B4-m1): a plain meta line draws with the role's base scaled by UIFontMetrics; the
                    // cascade must survive that scaling, here at the largest accessibility size.
                    let scaled = UIFontMetrics(forTextStyle: .body).scaledFont(
                        for: base, compatibleWith: UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge))
                    XCTAssertGreaterThan(scaled.pointSize, base.pointSize, "\(face) at \(weight) scaled")
                    XCTAssertEqual(drawnFrom(rune, base: scaled), TypeRole.runes, "\(name) in \(face) at \(weight), scaled to AX5")
                }
            }
        }
    }

    /// The negatives that make the check above mean something: a face without the cascade falls back to a system
    /// font for a rune, and the cascade leaves Latin text in its own face.
    func testTheCascadeOnlyCatchesRunes() throws {
        let bare = try XCTUnwrap(UIFont(name: TypeRole.sansRegular, size: 14))
        XCTAssertNotEqual(drawnFrom(Runes.separator, base: bare), TypeRole.runes, "a bare Plex Sans must fall back for a rune")
        let sans = TypeRole.base(.sans, size: 14, weight: 400, tabular: false)
        XCTAssertEqual(drawnFrom("A", base: sans), TypeRole.sansRegular)
        let cormorant = TypeRole.base(.cormorant, size: 14, weight: 600, tabular: false)
        XCTAssertEqual(drawnFrom("A", base: cormorant), TypeRole.cormorantSemiBold)
    }

    /// The rune role: fixed (the art's sizes today) unless it is asked to follow a text style.
    func testTheRuneRoleIsFixedOrScaledOnRequest() {
        XCTAssertEqual(TypeRole.runeUIFont(22, relativeTo: nil, category: .accessibilityExtraExtraExtraLarge).pointSize, 22)
        XCTAssertEqual(TypeRole.runeUIFont(13, relativeTo: .footnote, category: .large).pointSize, 13, accuracy: 0.01)
        XCTAssertGreaterThan(TypeRole.runeUIFont(13, relativeTo: .footnote, category: .accessibilityExtraExtraExtraLarge).pointSize, 13)
    }

    /// D-215: the six draw sites ask for the rune role (exact anchors), so none falls back to a text face's
    /// cascade by accident; every `.font(.system(` is banned from the art files by AboutTests' allowlist.
    func testEveryRuneDrawUsesTheRuneRole() throws {
        let sites: [(file: String, anchor: String)] = [
            ("SkiptiTabBar.swift", #"Text(verbatim: tab.rune).font(TypeRole.rune(ThemeArt.m("tabRune")))"#),
            ("ThemeArt.swift", #"Text(verbatim: Runes.ornament).font(TypeRole.rune(ThemeArt.m("ornamentRune")))"#),
            ("ThemeArt.swift", "Text(verbatim: prefix).font(TypeRole.rune(size, relativeTo: .footnote))"),
            ("ThemeArt.swift", "let font = TypeRole.rune(size, relativeTo: .body)"),
            ("ThemeArt.swift", "out[found].font = font"),
            ("ThemeMotion.swift", "Text(verbatim: p.glyph).font(TypeRole.rune(p.size))"),
            ("ThemeMotion.swift", #".font(TypeRole.rune(ThemeArt.m("heroWatermark")))"#), // D-226: the hero's watermark from the table
            ("ThemeMotion.swift", "Text(verbatim: Runes.futhark[i]).font(TypeRole.rune(SettledBurst.norseRune))"), // D-221: the burst's runes
            ("YouProgress.swift", ".font(TypeRole.rune(size))"), // D-221: Norse's rune ring round You's picture
            ("AccountHubScreen.swift", #".font(TypeRole.rune(ThemeArt.m("swatchRune")))"#), // D-222: Norse's theme swatch
            // D-245 (decision 192): the welcome's progress runes, and W1's places in Norse (the bar's own runes).
            ("Welcome.swift", ".font(TypeRole.rune(size))"),
            ("Welcome.swift", #"Text(verbatim: tab.rune).font(TypeRole.rune(ThemeArt.m("seal") * 0.6))"#),
            // D-249 (decision 164): Just me's ᛁ tile in Norse.
            ("Spending.swift", ".font(TypeRole.rune(size * 0.62))"),
        ]
        for site in sites {
            let text = try appSource(site.file)
            XCTAssertEqual(text.components(separatedBy: site.anchor).count - 1, 1, "\(site.file) lacks the rune role at: \(site.anchor)")
        }
        // And the rune role is drawn by the art files alone.
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path).filter({ $0.hasSuffix(".swift") })
        where !["ThemeArt.swift", "ThemeMotion.swift", "SkiptiTabBar.swift", "TypeSystem.swift", "YouProgress.swift",
                 "AccountHubScreen.swift", "Welcome.swift", "Spending.swift"].contains(file) {
            XCTAssertFalse(try appSource(file).contains("TypeRole.rune("), "\(file) draws runes itself")
        }
    }

    /// A 100 x 60 card at the card's chamfer. D-226: the chamfer is 10 (8 x 1.28); the golden is .drafts/d226/art.py
    /// section 46's `notched(100 x 60, 10)` (it was 8 pt and hand-worked before).
    func testTheNotchedCardsVertices() {
        let p = ArtGeometry.notched(CGRect(x: 0, y: 0, width: 100, height: 60), chamfer: ThemeArt.m("chamfer"))
        XCTAssertEqual(p, [CGPoint(x: 10, y: 0), CGPoint(x: 90, y: 0), CGPoint(x: 100, y: 10), CGPoint(x: 100, y: 50),
                           CGPoint(x: 90, y: 60), CGPoint(x: 10, y: 60), CGPoint(x: 0, y: 50), CGPoint(x: 0, y: 10)])
    }

    /// The negative: a chamfer larger than half the short side is clamped to it (10 x 10 -> 5).
    func testTheChamferNeverPassesHalfTheShortSide() {
        let p = ArtGeometry.notched(CGRect(x: 0, y: 0, width: 10, height: 10), chamfer: ThemeArt.m("chamfer"))
        XCTAssertEqual(p, [CGPoint(x: 5, y: 0), CGPoint(x: 5, y: 0), CGPoint(x: 10, y: 5), CGPoint(x: 10, y: 5),
                           CGPoint(x: 5, y: 10), CGPoint(x: 5, y: 10), CGPoint(x: 0, y: 5), CGPoint(x: 0, y: 5)])
    }

    // ---- D-243 (T7, plan section 5 test 9): Norse's card-edge glow ----

    /// The glow's bands are motion.py's (.drafts/d243/motion-lanes.out section 6, `edge bands`): 0.5 pt bands at
    /// 0.18 x Phi(-d / 3.0) (sigma 3.0 after the gap audit, .drafts/d243/motion-t7.out), twelve of them, the last at least
    /// one 8-bit step and the next (6.25 pt, 0.003350) under it; 6 pt of reach. The negatives: no peak, sigma or band width gives no glow; a tighter sigma reaches less.
    func testTheEdgeGlowBandsAreMotionPys() {
        XCTAssertEqual(ThemeArt.m("norseEdgeGlow"), 0.18, accuracy: 1e-12)
        XCTAssertEqual(ThemeArt.m("norseEdgeGlowSigma"), 3.0)
        XCTAssertEqual(ThemeArt.m("norseEdgeGlowBand"), 0.5)
        let want: [(CGFloat, Double)] = [
            (0.25, 0.084023), (0.75, 0.072233), (1.25, 0.060923), (1.75, 0.05037), (2.25, 0.040793), (2.75, 0.032339),
            (3.25, 0.025079), (3.75, 0.019017), (4.25, 0.014092), (4.75, 0.010201), (5.25, 0.007211), (5.75, 0.004975),
        ]
        let bands = ArtGeometry.glowBands()
        XCTAssertEqual(bands.count, want.count)
        for (b, w) in zip(bands, want) {
            XCTAssertEqual(b.offset, w.0, accuracy: 1e-12)
            XCTAssertEqual(b.alpha, w.1, accuracy: 6e-7, "band \(w.0)")
        }
        for (a, b) in zip(bands, bands.dropFirst()) { XCTAssertLessThan(b.alpha, a.alpha, "the glow falls outward") }
        XCTAssertGreaterThanOrEqual(bands.last?.alpha ?? 0, 1.0 / 255)
        XCTAssertLessThan(0.18 * erfc(6.25 / (3.0 * 2.0.squareRoot())) / 2, 1.0 / 255, "the next band is under a step")
        XCTAssertEqual(ArtGeometry.glowReach, 6, accuracy: 1e-12)
        XCTAssertTrue(ArtGeometry.glowBands(peak: 0).isEmpty)
        XCTAssertTrue(ArtGeometry.glowBands(sigma: 0).isEmpty)
        XCTAssertTrue(ArtGeometry.glowBands(band: 0).isEmpty)
        XCTAssertEqual(ArtGeometry.glowBands(sigma: 2).count, 8, "motion.py at sigma 2: 4 pt")
    }

    /// The grown outline: vertices from .drafts/d243/t7/geometry.py (each edge line of the 100 x 60 card offset outward
    /// and neighbours intersected, not this closed form), and every side of every band's outline, the 45 degree cuts
    /// included, exactly its offset from the card's matching side. The negative: the plan's first formula,
    /// c + d (sqrt 2 - 1), leaves the cuts 1.12 d out. The chamfer is clamped before it grows.
    func testTheGlowsOutlineGrowsEverySideByItsOffset() {
        let card = CGRect(x: 0, y: 0, width: 100, height: 60)
        let c = ThemeArt.m("chamfer")
        let goldens: [(CGFloat, [(CGFloat, CGFloat)])] = [
            (0.25, [(9.896447, -0.25), (90.103553, -0.25), (100.25, 9.896447), (100.25, 50.103553), (90.103553, 60.25),
                    (9.896447, 60.25), (-0.25, 50.103553), (-0.25, 9.896447)]),
            (5.75, [(7.618272, -5.75), (92.381728, -5.75), (105.75, 7.618272), (105.75, 52.381728), (92.381728, 65.75),
                    (7.618272, 65.75), (-5.75, 52.381728), (-5.75, 7.618272)]),
        ]
        for (d, want) in goldens {
            let p = ArtGeometry.grownNotched(card, chamfer: c, by: d)
            XCTAssertEqual(p.count, 8)
            for (a, b) in zip(p, want) {
                XCTAssertEqual(a.x, b.0, accuracy: 1e-6, "d \(d)")
                XCTAssertEqual(a.y, b.1, accuracy: 1e-6, "d \(d)")
            }
        }
        let base = ArtGeometry.notched(card, chamfer: c)
        func distance(_ p: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGFloat {
            abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / hypot(b.x - a.x, b.y - a.y)
        }
        for band in ArtGeometry.glowBands() {
            let g = ArtGeometry.grownNotched(card, chamfer: c, by: band.offset)
            for i in 0 ..< 8 {
                let j = (i + 1) % 8
                XCTAssertEqual(distance(g[i], base[i], base[j]), band.offset, accuracy: 1e-9, "side \(i) at \(band.offset)")
                XCTAssertEqual(distance(g[j], base[i], base[j]), band.offset, accuracy: 1e-9, "side \(i) at \(band.offset)")
            }
        }
        let wrong = ArtGeometry.notched(card.insetBy(dx: -4, dy: -4), chamfer: c + 4 * (2.0.squareRoot() - 1))
        XCTAssertEqual(distance(wrong[7], base[7], base[0]), 4.485, accuracy: 0.001, "the plan's formula: 1.12 d")
        let small = ArtGeometry.grownNotched(CGRect(x: 0, y: 0, width: 10, height: 10), chamfer: c, by: 1)
        XCTAssertEqual(small[0].x, -1 + 5 + (2 - 2.0.squareRoot()), accuracy: 1e-9, "clamped to 5, then grown")
        XCTAssertEqual(small[0].y, -1)
    }

    /// D-243 (stage 5): the sign-in hero is its Section's HEADER, not a row. iOS 26+ clips a row to its rounded cell,
    /// which cut Norse's notched corners round and its edge glow away entirely (0 pixels of glow; Android's twin shows
    /// both); a header is not clipped. It keeps the row's place by the Form's top margin above a first row (35 pt,
    /// measured on iOS 27: the still frame is identical outside the hero's box in all three themes), and no row
    /// modifier is left on it. The padding is outside the glow, so the glow hugs the card, not the padding.
    func testTheSignInHeroIsAHeaderNotARow() throws {
        XCTAssertEqual(SignInLayout.heroTop, 35)
        let fields = try slice(try appSource("SignInScreen.swift"), from: "private var fields: some View {",
                               to: "\n        Section {\n            // D-132/D-138")
        XCTAssertEqual(fields.components(separatedBy: "} header: {").count - 1, 1, "one header: the hero's")
        let header = try XCTUnwrap(fields.components(separatedBy: "        Section {\n        } header: {\n").dropFirst().first,
                                   "an empty Section whose header is the hero")
        var rest = Substring(header)
        for needle in ["Text(verbatim: SignInCopy.heroTitle)",
                       ".background { HeroArt().clipShape(ThemeArt.cardShape(AppearanceState.shared.theme)) }",
                       ".norseEdgeGlow()", ".accessibilityElement(children: .combine)",
                       ".accessibilityRemoveTraits(.isHeader)", ".padding(.top, refused ? 0 : SignInLayout.heroTop)", ".textCase(nil)",
                       ".listRowInsets(EdgeInsets())"] {
            let range = try XCTUnwrap(rest.range(of: needle), "in order: \(needle)")
            rest = rest[range.upperBound...]
        }
        for banned in [".listRowBackground(", ".listRowSeparator("] {
            XCTAssertFalse(fields.contains(banned), "the hero is no row: \(banned)")
        }
    }

    /// D-243 fix wave (I1): floored page-ground text keeps at least the glow's reach (`ArtGeometry.glowReach`) from a
    /// Norse card edge: every glow band fails textDim over a flaring rune (motion.py 1f). The tightest site, Add a
    /// person's field labels (People.swift `field()`, 6 above their card), and the closest of the 8 pt sites
    /// (.drafts/d243/gap-audit.md), Appearance's Text size label above its card, are read from the source and held to it.
    func testPageTextKeepsTheGlowsReachFromANorseCard() throws {
        func spacing(_ file: String, _ before: String, _ after: String) throws -> CGFloat {
            let source = try appSource(file)
            XCTAssertEqual(source.components(separatedBy: before).count - 1, 1, "\(file): one site")
            let tail = try XCTUnwrap(source.components(separatedBy: before).dropFirst().first, file)
            let number = try XCTUnwrap(tail.components(separatedBy: after).first, file)
            XCTAssertTrue(tail.hasPrefix(number + after), "\(file): the label, then its card")
            return CGFloat(try XCTUnwrap(Double(number), "\(file): the spacing is a number: \(number)"))
        }
        let field = try spacing("People.swift", """
            private func field<Content: View>(_ label: String, @ViewBuilder _ content: () -> Content) -> some View {
                    VStack(alignment: .leading, spacing:\u{20}
            """, """
            ) {
                        Text(verbatim: label).sectionLabel().foregroundStyle(Tokens.textDim)
                        content().font(TypeRole.body()).padding(12).glassCard(padding: 0)
            """)
        XCTAssertGreaterThanOrEqual(field, ArtGeometry.glowReach, "Add a person's field labels clear the glow")
        let textSize = try spacing("AccountHubScreen.swift", """
            case .textSize:
                        VStack(alignment: .leading, spacing:\u{20}
            """, """
            ) {
                            Text(verbatim: AppearanceCopy.cards[2]).sectionLabel().foregroundStyle(Tokens.textDim)
                            card {
            """)
        XCTAssertGreaterThanOrEqual(textSize, ArtGeometry.glowReach, "the Text size label clears the glow")
        XCTAssertEqual(ArtGeometry.glowReach, 6, "motion.py's CLEAR DISTANCE")
    }

    /// The Norse cards draw the glow: GlassCard's notched case beneath its fill, each hero outside its clipped art (so
    /// the clip leaves it whole); the glass and framed cases never; the glow is drawn bands, never a blur or a shadow.
    func testTheNorseCardsDrawTheEdgeGlow() throws {
        let notched = try styleCase("DesignTokens.swift", owner: "struct GlassCard: ViewModifier", "notched")
        XCTAssertEqual(notched.components(separatedBy: ".background { NorseEdgeGlow() }").count - 1, 1, "beneath the fill")
        for banned in [".shadow(", ".blur(", "dropShadow", "addFilter"] {
            XCTAssertFalse(notched.contains(banned), "no \(banned) in the Norse card")
        }
        for other in ["glass", "framed"] {
            XCTAssertFalse(try styleCase("DesignTokens.swift", owner: "struct GlassCard: ViewModifier", other).contains("EdgeGlow"), other)
        }
        let art = try appSource("ThemeArt.swift")
        let view = try XCTUnwrap(art.components(separatedBy: "struct NorseEdgeGlow: View {").dropFirst().first?
            .components(separatedBy: "\n}\n").first)
        for needle in ["ArtGeometry.glowBands()", "ArtGeometry.grownNotched(card, chamfer: ThemeArt.m(\"chamfer\"), by: band.offset)",
                       "with: .color(Tokens.accent.opacity(band.alpha))", "lineWidth: ThemeArt.m(\"norseEdgeGlowBand\")",
                       ".padding(-reach)", ".allowsHitTesting(false)", ".accessibilityHidden(true)"] {
            XCTAssertTrue(view.contains(needle), needle)
        }
        for banned in [".shadow(", ".blur(", "addFilter", "Text("] { XCTAssertFalse(view.contains(banned), banned) }
        XCTAssertTrue(art.contains("if CardStyle.of(AppearanceState.shared.theme) == .notched { NorseEdgeGlow() }"),
                      "the heroes' glow is Norse's alone")
        let clipped = ".background { HeroArt().clipShape(ThemeArt.cardShape(AppearanceState.shared.theme)) }"
        for file in ["OverviewScreen.swift", "SignInScreen.swift"] {
            let source = try appSource(file)
            XCTAssertEqual(source.components(separatedBy: ".norseEdgeGlow()").count - 1, 1, file)
            let after = try XCTUnwrap(source.components(separatedBy: clipped).dropFirst().first, file)
            XCTAssertTrue(after.split(separator: "\n").prefix(2).contains { $0.contains(".norseEdgeGlow()") },
                          "\(file): the glow sits right after the clipped art, outside its clip")
        }
        XCTAssertFalse(try appSource("ThemeMotion.swift").contains("EdgeGlow"), "never inside HeroArt's clip")
        // Every draw of it: the card and the heroes' modifier, nowhere else.
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        var views = 0, heroes = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path).filter({ $0.hasSuffix(".swift") }) {
            let text = try appSource(file)
            views += text.components(separatedBy: "NorseEdgeGlow()").count - 1
            heroes += text.components(separatedBy: ".norseEdgeGlow()").count - 1
        }
        XCTAssertEqual(views, 2)
        XCTAssertEqual(heroes, 2)
    }

    /// The diamond's tips are the box's edge midpoints: top, right, bottom, left.
    func testTheDiamondsVertices() {
        XCTAssertEqual(ArtGeometry.diamond(CGRect(x: 0, y: 0, width: 48, height: 48)),
                       [CGPoint(x: 24, y: 0), CGPoint(x: 48, y: 24), CGPoint(x: 24, y: 48), CGPoint(x: 0, y: 24)])
    }

    /// The Look Book's clip-path on 72 x 64: 0.02 x 64 = 1.28, 0.98 x 64 = 62.72.
    func testTheHexagonsVertices() {
        let p = ArtGeometry.hexagon(CGRect(x: 0, y: 0, width: 72, height: 64))
        let want = [CGPoint(x: 18, y: 1.28), CGPoint(x: 54, y: 1.28), CGPoint(x: 72, y: 32),
                    CGPoint(x: 54, y: 62.72), CGPoint(x: 18, y: 62.72), CGPoint(x: 0, y: 32)]
        XCTAssertEqual(p.count, want.count)
        for (a, b) in zip(p, want) {
            XCTAssertEqual(a.x, b.x, accuracy: 0.0001)
            XCTAssertEqual(a.y, b.y, accuracy: 0.0001)
        }
    }

    /// Eight strokes at the art's `frameLength`, `frameInset` in from each corner of a 100 x 60 card, every point inside
    /// the card. D-226: 12 pt arms 4 pt in (9 and 3 x 1.28; they were 8 and 3); the golden is .drafts/d226/art.py
    /// section 46's `frameMarks(100 x 60, 4, 12)`, all eight.
    func testTheFrameMarks() {
        let rect = CGRect(x: 0, y: 0, width: 100, height: 60)
        let s = ArtGeometry.frameMarks(rect, inset: ThemeArt.m("frameInset"), length: ThemeArt.m("frameLength"))
        XCTAssertEqual(s.count, 8)
        let want: [(CGPoint, CGPoint)] = [
            (CGPoint(x: 4, y: 4), CGPoint(x: 16, y: 4)), (CGPoint(x: 4, y: 4), CGPoint(x: 4, y: 16)),
            (CGPoint(x: 96, y: 4), CGPoint(x: 84, y: 4)), (CGPoint(x: 96, y: 4), CGPoint(x: 96, y: 16)),
            (CGPoint(x: 96, y: 56), CGPoint(x: 84, y: 56)), (CGPoint(x: 96, y: 56), CGPoint(x: 96, y: 44)),
            (CGPoint(x: 4, y: 56), CGPoint(x: 16, y: 56)), (CGPoint(x: 4, y: 56), CGPoint(x: 4, y: 44)),
        ]
        for (got, exp) in zip(s, want) {
            XCTAssertEqual(got.0, exp.0)
            XCTAssertEqual(got.1, exp.1)
        }
        for (a, b) in s { XCTAssertTrue(rect.contains(a) && rect.contains(b)) }
    }

    /// D-226 (.drafts/d226/art.py section 46): the grid at the art's scaled pitch, 18 (14 x 1.28): 402 x 874 holds 22 x 49
    /// = 1,078 dots; 36 x 40 holds four, at (9, 9) (27, 9) (9, 27) (27, 27). The negative: not the 1,798 of pitch 14.
    func testGridAtTheScaledPitch() {
        let pitch = ThemeArt.m("gridPitch")
        XCTAssertEqual(ArtGeometry.gridDots(CGSize(width: 402, height: 874), pitch: pitch).count, 1078)
        XCTAssertNotEqual(ArtGeometry.gridDots(CGSize(width: 402, height: 874), pitch: pitch).count, 1798)
        XCTAssertEqual(ArtGeometry.gridDots(CGSize(width: 36, height: 40), pitch: pitch),
                       [CGPoint(x: 9, y: 9), CGPoint(x: 27, y: 9), CGPoint(x: 9, y: 27), CGPoint(x: 27, y: 27)])
    }

    /// D-226 (L35, Q18 a): the Norse card's edge is its four straight sides, each 0.5 in (the 1 pt line inside the shape),
    /// ending on the diagonals. Goldens: .drafts/d226/art.py section 52. Negatives: exactly four segments (never the eight
    /// sides of `notched`), and none diagonal.
    func testNotchEdges() {
        func pairs(_ s: [(CGPoint, CGPoint)]) -> [[CGFloat]] { s.map { [$0.0.x, $0.0.y, $0.1.x, $0.1.y] } }
        let card = ArtGeometry.notchEdges(CGRect(x: 0, y: 0, width: 100, height: 60), chamfer: ThemeArt.m("chamfer"))
        XCTAssertEqual(pairs(card), [[9.5, 0.5, 90.5, 0.5], [99.5, 9.5, 99.5, 50.5], [90.5, 59.5, 9.5, 59.5], [0.5, 50.5, 0.5, 9.5]])
        let tiny = ArtGeometry.notchEdges(CGRect(x: 0, y: 0, width: 10, height: 10), chamfer: ThemeArt.m("chamfer"))
        XCTAssertEqual(pairs(tiny), [[4.5, 0.5, 5.5, 0.5], [9.5, 4.5, 9.5, 5.5], [5.5, 9.5, 4.5, 9.5], [0.5, 5.5, 0.5, 4.5]],
                       "clamped to 5")
        for rect in [CGRect(x: 0, y: 0, width: 100, height: 60), CGRect(x: 3, y: 7, width: 240, height: 88)] {
            let s = ArtGeometry.notchEdges(rect, chamfer: ThemeArt.m("chamfer"))
            XCTAssertEqual(s.count, 4, "four straight sides, not the eight of notched")
            for (a, b) in s { XCTAssertTrue(a.x == b.x || a.y == b.y, "a segment is horizontal or vertical: \(a) \(b)") }
        }
        // The shape draws exactly these segments.
        let path = NotchEdges(chamfer: ThemeArt.m("chamfer")).path(in: CGRect(x: 0, y: 0, width: 100, height: 60))
        XCTAssertEqual(path.boundingRect, CGRect(x: 0.5, y: 0.5, width: 99, height: 59))
    }

    /// The text of `case .<name>:` inside the first `struct <owner>` of `file`, up to the next `case .`.
    private func styleCase(_ file: String, owner: String, _ name: String) throws -> String {
        let body = try XCTUnwrap(try appSource(file).components(separatedBy: owner).dropFirst().first, owner)
        let after = try XCTUnwrap(body.components(separatedBy: "case .\(name):").dropFirst().first, "\(owner) case .\(name)")
        return try XCTUnwrap(after.components(separatedBy: "case .").first)
    }

    /// D-226 (L35, Q18 a, D28): a Norse card's edge is drawn on its four straight sides through `NotchEdges` (inside the
    /// shape, the diagonals uncut by any line), in the card and in the photo frame. The negative: no app file strokes a
    /// whole notched outline in the card edge's colour (today's way, which also straddled the shape's edge).
    /// D-226 fix wave (M1): the sweep names what MAY draw in `norseEdge` instead of what may not: every
    /// `.stroke(` / `.strokeBorder(` / `.border(` in `ThemeArt.norseEdge` is on a `NotchEdges(...)` (the straight sides)
    /// or a `Diamond(...)` (the tile), so a whole outline on any receiver (the hero's `cardShape(.norse)` was one) is red.
    func testTheNorseCardEdgeIsTheStraightSides() throws {
        let card = try styleCase("DesignTokens.swift", owner: "struct GlassCard: ViewModifier", "notched")
        let photo = try styleCase("ThemeArt.swift", owner: "struct PhotoFrame: ViewModifier", "notched")
        for (name, text) in [("GlassCard", card), ("PhotoFrame", photo)] {
            XCTAssertEqual(text.components(separatedBy: "NotchEdges(").count - 1, 1, "\(name) edges the straight sides")
            XCTAssertTrue(text.contains(".stroke(ThemeArt.norseEdge, lineWidth: 1)"), "\(name): 1 pt in the edge colour")
            XCTAssertTrue(text.contains(".clipShape("), "\(name): the edge is clipped to the notched fill")
        }
        let whole = try NSRegularExpression(
            pattern: #"(NotchedRectangle\((?:[^()\n]|\([^()\n]*\))*\)|\bnotch)\s*\.stroke\(ThemeArt\.norseEdge"#)
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        var seen = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path).filter({ $0.hasSuffix(".swift") }) {
            let text = try appSource(file)
            seen += 1
            XCTAssertEqual(whole.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text)), 0,
                           "\(file) strokes a whole notched outline in norseEdge")
        }
        XCTAssertGreaterThan(seen, 40, "the walk saw the app files")
        // The regex itself catches today's two forms.
        for old in ["notch.stroke(ThemeArt.norseEdge, lineWidth: 1)",
                    #"NotchedRectangle(chamfer: ThemeArt.m("chamfer")).stroke(ThemeArt.norseEdge, lineWidth: 1)"#,
                    "PanelNotchedRectangle()\n    .stroke(ThemeArt.norseEdge, lineWidth: 1)"] {
            XCTAssertEqual(whole.numberOfMatches(in: old, range: NSRange(old.startIndex..., in: old)), 1, old)
        }
        // M1: every norseEdge draw is on an allowed receiver, across the app.
        let edgeDraw = try NSRegularExpression(pattern: #"\.(stroke|strokeBorder|border)\(ThemeArt\.norseEdge\b"#)
        let allowedDraw = try NSRegularExpression(
            pattern: #"\b(NotchEdges|Diamond)\((?:[^()\n]|\([^()\n]*\))*\)\s*\.stroke\(ThemeArt\.norseEdge\b"#)
        func draws(_ re: NSRegularExpression, _ text: String) -> Int {
            re.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
        }
        var total = 0, allowedTotal = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path).filter({ $0.hasSuffix(".swift") }) {
            let text = try appSource(file)
            let all = draws(edgeDraw, text), ok = draws(allowedDraw, text)
            XCTAssertEqual(all, ok, "\(file) draws norseEdge on a receiver that is not NotchEdges or Diamond")
            total += all
            allowedTotal += ok
        }
        // The card, the photo frame, the hero and the diamond tile: the sweep sees them all.
        XCTAssertGreaterThanOrEqual(allowedTotal, 4, "the sweep found the edges")
        XCTAssertEqual(total, allowedTotal)
        // Proven against the old hero line (and the other old forms): a draw, never an allowed one.
        for old in ["ThemeArt.cardShape(.norse).stroke(ThemeArt.norseEdge, lineWidth: 2).clipShape(ThemeArt.cardShape(.norse))",
                    "notch.stroke(ThemeArt.norseEdge, lineWidth: 1)",
                    "Rectangle().strokeBorder(ThemeArt.norseEdge, lineWidth: 1)",
                    "PanelNotchedRectangle()\n    .stroke(ThemeArt.norseEdge, lineWidth: 1)"] {
            XCTAssertEqual(draws(edgeDraw, old), 1, old)
            XCTAssertEqual(draws(allowedDraw, old), 0, old)
        }
        for fine in [#"NotchEdges(chamfer: ThemeArt.m("chamfer")).stroke(ThemeArt.norseEdge, lineWidth: 1).clipShape(notch)"#,
                     #"Diamond(radius: ThemeArt.m("tileRadius")).stroke(ThemeArt.norseEdge, lineWidth: 1)"#] {
            XCTAssertEqual(draws(allowedDraw, fine), 1, fine)
        }
    }

    /// D-226 (L36): Aurora's glass reads its film, rim and shadow from the art table (iOS is the reference, so no pixel
    /// moves); the literals are gone from the `.glass` case.
    func testTheGlassReadsTheTable() throws {
        let glass = try styleCase("DesignTokens.swift", owner: "struct GlassCard: ViewModifier", "glass")
        for key in ["glassFilm", "glassRim", "glassShadowSigma", "glassShadowY", "glassShadowAlpha"] {
            XCTAssertTrue(glass.contains(#"ThemeArt.m("\#(key)")"#), key)
        }
        for literal in ["opacity(0.78)", "opacity(0.35)", "opacity(0.06)", "radius: 12", "y: 4"] {
            XCTAssertFalse(glass.contains(literal), "the glass keeps a literal: \(literal)")
        }
        XCTAssertTrue(glass.contains(".blendMode(.overlay)"), "the rim blends as before")
        XCTAssertTrue(glass.contains("strokeBorder("), "the rim inside the shape (D28)")
    }

    /// D-226 (L31): CSS angles as unit points. Goldens: .drafts/d226/art.py section 49 (1e-4; the bolt's 150 on 72 x 64
    /// reproduces C1's values to 1e-3). Negative: 160 on a square is not the corner-to-corner pair.
    func testCssGradient() {
        func check(_ deg: Double, _ w: Double, _ h: Double, _ want: [Double], _ accuracy: Double) {
            let p = CssGradient.unitPoints(degrees: deg, width: w, height: h)
            for (got, exp) in zip([p.start.x, p.start.y, p.end.x, p.end.y], want) {
                XCTAssertEqual(Double(got), exp, accuracy: accuracy, "\(deg) on \(w) x \(h)")
            }
        }
        check(160, 362, 200, [0.3527, -0.2324, 0.6473, 1.2324], 1e-4)
        check(160, 100, 100, [0.2808, -0.1022, 0.7192, 1.1022], 1e-4)
        check(150, 72, 64, [Double(ThemeArt.m("boltStartX")), Double(ThemeArt.m("boltStartY")), Double(ThemeArt.m("boltEndX")),
                            Double(ThemeArt.m("boltEndY"))], 1e-3)
        check(150, 72, 64, [0.183, -0.119, 0.817, 1.119], 1e-3)
        let square = CssGradient.unitPoints(degrees: 160, width: 100, height: 100)
        XCTAssertFalse(square.start == .topLeading && square.end == .bottomTrailing)
    }

    /// Dots at the centre of each 14 pt cell (the Look Book's radial-gradient tiles): 7, 21, ...; a dot is kept
    /// only when its centre is inside the size. 28 x 30: x 7, 21; y 7, 21 (35 is out). 402 x 874: x 7...399 (29),
    /// y 7...861 (62), 1,798 dots. (D-226: the art's pitch is now 18, testGridAtTheScaledPitch; this pins the
    /// geometry at a literal pitch, D-213's.)
    func testTheGridsDots() {
        XCTAssertEqual(ArtGeometry.gridDots(CGSize(width: 28, height: 30), pitch: 14),
                       [CGPoint(x: 7, y: 7), CGPoint(x: 21, y: 7), CGPoint(x: 7, y: 21), CGPoint(x: 21, y: 21)])
        // A centre ON the edge is not inside: 21 x 21 holds one dot, (7, 7), not four (21 is not < 21).
        XCTAssertEqual(ArtGeometry.gridDots(CGSize(width: 21, height: 21), pitch: 14), [CGPoint(x: 7, y: 7)])
        XCTAssertEqual(ArtGeometry.gridDots(CGSize(width: 402, height: 874), pitch: 14).count, 1798)
        XCTAssertTrue(ArtGeometry.gridDots(.zero, pitch: 14).isEmpty)
    }

    func testEachThemesDecisions() {
        XCTAssertEqual(AppTheme.allCases.map(TabIndicator.of), [.pill, .underline, .outline])
        XCTAssertEqual(AppTheme.allCases.map(TabIndicator.drawsRunes), [false, true, false])
        // D-219 (L13): Aurora's pill is filled accent, so its glyph is onAccent; Norse keeps the gold; Vardr Labs the ring.
        XCTAssertEqual(AppTheme.allCases.map(TabIndicator.activeGlyph), [.onAccent, .accent, .ring])
        // D-219 (L15): Aurora's bar is glass; Norse and Vardr Labs are solid surface.
        XCTAssertEqual(AppTheme.allCases.map(TabBarGround.of), [.glass, .solid, .solid])
        XCTAssertEqual(TabBarGround.filmAlpha, 0.72)
        XCTAssertEqual(AppTheme.allCases.map(AddButtonArt.of), [.circle, .diamond, .bolt])
        XCTAssertEqual(AppTheme.allCases.map(CardStyle.of), [.glass, .notched, .framed])
        XCTAssertEqual(AppTheme.allCases.map { TileShape.of($0, person: false) }, [.rounded, .diamond, .square])
        XCTAssertEqual(AppTheme.allCases.map { TileShape.of($0, person: true) }, [.circle, .diamond, .square])
        XCTAssertEqual(AppTheme.allCases.map(ButtonLook.of), [.filled, .filled, .outlined])
        XCTAssertEqual(AppTheme.allCases.map(Ground.of), [.curtains, .runes, .grid])
        XCTAssertEqual(AppTheme.allCases.map(SectionMark.prefix), [nil, "\u{16DF} ", nil])
        // D-221: the avatar's edge and face are `AvatarStyle.edgeHex` (testAvatarFillsAndEdge) and the display face at
        // 700 in every theme (testAvatarsTakeTheirFill); ringHex and initialsUseDisplayFace are gone.
    }

    /// D-219 (L18): the Look Book draws its icons at stroke-width 2.4 and its plus at 2.8 (LB 335 `.pi`).
    func testTheLucideStrokeIsTheBolderOne() throws {
        XCTAssertEqual(LucideStroke.width, 2.4)
        XCTAssertEqual(LucideStroke.plus, 2.8)
        // Source guard: every Lucide draw reads the one constant.
        let icon = try appSource("LucideIcon.swift")
        XCTAssertEqual(count(icon, "cg.setLineWidth(LucideStroke.width)"), 1, "the template and ink images stroke at the constant")
        XCTAssertEqual(count(icon, "insetBy(dx: -LucideStroke.width / 2, dy: -LucideStroke.width / 2)"), 1, "the ink inset is half the stroke")
        XCTAssertEqual(count(icon, "var stroke: CGFloat = LucideStroke.width"), 1, "LucideIcon takes the stroke, defaulting to the constant")
        XCTAssertEqual(count(icon, "lineWidth: stroke * size / 24"), 1, "LucideIcon strokes at stroke * size / 24")
        XCTAssertEqual(count(icon, "setLineWidth(2)"), 0, "no literal stroke of 2 left")
        XCTAssertEqual(count(icon, "lineWidth: 2 *"), 0, "no literal stroke of 2 left in the view")
        XCTAssertEqual(count(icon, "insetBy(dx: -1"), 0, "no literal ink inset of 1 left")
        // The + glyph alone takes the heavier stroke.
        let face = try addButtonFaceSource()
        XCTAssertEqual(count(face, "LucideIcon(glyph: .plus, size: 28, stroke: LucideStroke.plus)"), 1, "the plus is stroked at LucideStroke.plus")
    }

    /// D-219 (L17): every face sits in one 64 x 64 footprint, so every bar is 70 tall (6 + 64) and every face's centre
    /// is 50 above the row's bottom (32 + the 18 lift). The diamond is the circle's square turned 45 degrees: its box is
    /// 64 x sqrt(2) = 90.51 (art.py section 12, re-derived there), which overflows the footprint, and its reach from the
    /// + centre (half the box, 45.25) stays inside the neighbour glyph's near edge on the narrowest phone (320 wide).
    func testThePlusFootprintAndFaces() throws {
        XCTAssertEqual(AddButtonGeometry.footprint, 64)
        XCTAssertEqual(AddButtonGeometry.lift, 18)
        XCTAssertEqual(AddButtonGeometry.faceSize(.circle), CGSize(width: 64, height: 64))
        XCTAssertEqual(AddButtonGeometry.faceSize(.bolt), CGSize(width: 72, height: 64))
        let diamond = AddButtonGeometry.faceSize(.diamond)
        XCTAssertEqual(diamond.width, 90.51, accuracy: 0.01)
        XCTAssertEqual(diamond.height, 90.51, accuracy: 0.01)
        XCTAssertEqual(diamond.width, ThemeArt.m("diamondSide") * sqrt(2), accuracy: 1e-9)
        XCTAssertEqual(AddButtonGeometry.footprint / 2 + AddButtonGeometry.lift, 50)
        // The bolt's own box is the metrics' (72 x 64), the circle's the footprint's.
        XCTAssertEqual(AddButtonGeometry.faceSize(.bolt), CGSize(width: ThemeArt.m("boltWidth"), height: ThemeArt.m("boltHeight")))
        // The diamond clears its neighbours at 320 wide: five slots, the 22 pt glyph at 1.08, a 12 pt side padding.
        let reach = ThemeArt.m("diamondSide") * sqrt(2) / 2
        let neighbourEdge = (320 - 12) / 5 - 11 * 1.08
        XCTAssertEqual(reach, 45.25, accuracy: 0.01)
        XCTAssertEqual(neighbourEdge, 49.72, accuracy: 0.01)
        XCTAssertLessThan(reach, neighbourEdge)
        // The negative: the old side (50) is not the diamond's.
        XCTAssertNotEqual(ThemeArt.m("diamondSide"), 50)
        // Source guard: the face is laid out in the footprint once, outside the switch; the diamond's own box is the geometry's.
        let face = try addButtonFaceSource()
        let footprint = ".frame(width: AddButtonGeometry.footprint, height: AddButtonGeometry.footprint)"
        XCTAssertEqual(count(face, footprint), 1, "the footprint frame is applied once")
        let inSwitch = try XCTUnwrap(face.components(separatedBy: "switch art {").dropFirst().first?
            .components(separatedBy: "\n        }\n").first)
        XCTAssertEqual(count(inSwitch, "AddButtonGeometry.footprint"), 0, "the footprint frame is outside the switch")
        let diamondCase = try addButtonCase("diamond")
        XCTAssertTrue(diamondCase.contains("AddButtonGeometry.faceSize(.diamond)"), "the diamond's box is the geometry's")
        XCTAssertFalse(diamondCase.contains("sqrt(2)"), "the diamond no longer works out its own box")
    }

    /// D-219 (L16): the circle takes the diamond's neutral shadow (black at 0.2, radius 7, y 6), not an accent glow.
    func testThePlusShadowIsNeutral() throws {
        XCTAssertEqual(AddButtonShadow.alpha, 0.2)
        XCTAssertEqual(AddButtonShadow.radius, 7)
        XCTAssertEqual(AddButtonShadow.y, 6)
        // Source guard: the circle and the diamond both draw it from the constants; no accent glow is left in the face.
        let face = try addButtonFaceSource()
        let shadow = ".shadow(color: Color.black.opacity(AddButtonShadow.alpha), radius: AddButtonShadow.radius, y: AddButtonShadow.y)"
        XCTAssertEqual(count(face, shadow), 2, "circle and diamond")
        XCTAssertTrue(try addButtonCase("circle").contains(shadow), "the circle's shadow")
        XCTAssertTrue(try addButtonCase("diamond").contains(shadow), "the diamond's shadow")
        XCTAssertEqual(count(face, "Tokens.accent.opacity"), 0, "no accent glow")
    }

    /// D-219 (L14): the bolt's glyph is the Look Book's reticle (LB 335 `.vp`): four arms and a filled centre dot of
    /// radius 1.3, as two path strings both shells draw. The arms span 3.5 to 20.5 on both axes (17 units); the dot is
    /// the circle 10.7 to 13.3 (2.6). Bounds are read tight from the parsed paths (the arcs become Beziers whose control
    /// points lie outside the circle, so the bounding box of the PATH, not of its control points).
    func testTheReticleIsTheLookBooksDrawing() throws {
        XCTAssertEqual(ReticleGlyph.arms, "M3.5 12h5.5M15 12h5.5M12 3.5V9M12 15v5.5")
        XCTAssertEqual(ReticleGlyph.dot, "M10.7 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0")
        let arms = try SVGPath.parse(ReticleGlyph.arms).cgPath.boundingBoxOfPath
        XCTAssertEqual(arms.minX, 3.5, accuracy: 0.01)
        XCTAssertEqual(arms.minY, 3.5, accuracy: 0.01)
        XCTAssertEqual(arms.width, 17, accuracy: 0.01)
        XCTAssertEqual(arms.height, 17, accuracy: 0.01)
        let dot = try SVGPath.parse(ReticleGlyph.dot).cgPath.boundingBoxOfPath
        XCTAssertEqual(dot.minX, 10.7, accuracy: 0.01)
        XCTAssertEqual(dot.minY, 10.7, accuracy: 0.01)
        XCTAssertEqual(dot.width, 2.6, accuracy: 0.01)
        XCTAssertEqual(dot.height, 2.6, accuracy: 0.01)
        // 24 px on the Look Book's 54 px bolt, times the shell's 72 / 54.
        XCTAssertEqual(ThemeArt.m("reticleGlyph"), 32)
        // Source guard: the bolt draws the reticle at the metric; the circle and the diamond draw the Lucide plus.
        let bolt = try addButtonCase("bolt")
        XCTAssertTrue(bolt.contains("ReticleGlyph(size: ThemeArt.m(\"reticleGlyph\"))"), "the bolt draws the reticle")
        XCTAssertFalse(bolt.contains("LucideIcon") || bolt.contains("plusGlyph"), "the bolt draws no Lucide plus")
        let face = try addButtonFaceSource()
        XCTAssertEqual(count(face, "ReticleGlyph("), 1, "the reticle is drawn once")
        XCTAssertEqual(count(face, "LucideIcon(glyph: .plus"), 1, "the plus is drawn once, in the shared plus glyph")
        XCTAssertTrue(try addButtonCase("circle").contains("plusGlyph"), "the circle draws the plus")
        XCTAssertTrue(try addButtonCase("diamond").contains("plusGlyph"), "the diamond draws the plus")
        // D-219 fix wave (M-1): the reticle's stroke is the shared constant, round, and the dot is filled.
        let art = try appSource("ThemeArt.swift")
        XCTAssertEqual(count(art, ".stroke(style: StrokeStyle(lineWidth: LucideStroke.width * size / 24, lineCap: .round, lineJoin: .round))"), 1,
                       "the reticle's arms stroke at the shared width, round")
        XCTAssertEqual(count(art, "ReticleShape(d: Self.dot).fill()"), 1, "the reticle's dot is filled")
    }

    /// D-219 (L13, L15): the bar draws the decided ground and Aurora's filled accent pill. Source guard (the drawing
    /// itself is looked at on the simulator): the ground comes from `TabBarGround.of`, the material is Aurora's alone, the
    /// hairline runs on top of every ground, the active glyph tint comes from `TabIndicator.activeGlyph`.
    func testTheTabBarDrawsTheDecidedGroundAndPill() throws {
        let bar = try appSource("SkiptiTabBar.swift")
        XCTAssertTrue(bar.contains("TabBarGround.of("), "the ground is the decision's")
        XCTAssertTrue(bar.contains("TabIndicator.activeGlyph("), "the active glyph's tint is the decision's")
        XCTAssertEqual(count(bar, ".ultraThinMaterial"), 1, "the material appears once")
        let glass = try XCTUnwrap(bar.components(separatedBy: "case .glass:").dropFirst().first?
            .components(separatedBy: "case .solid:").first, "the .glass case")
        XCTAssertEqual(count(glass, ".ultraThinMaterial"), 1, "the material is inside the .glass case")
        XCTAssertTrue(glass.contains("TabBarGround.filmAlpha"), "the film is the named constant")
        let hairline = "Rectangle().fill(Tokens.hairline).frame(height: 1)"
        XCTAssertEqual(count(bar, hairline), 1, "one hairline")
        let solid = try XCTUnwrap(bar.components(separatedBy: "case .solid:").dropFirst().first?
            .components(separatedBy: "\n        }\n").first)
        XCTAssertFalse(glass.contains(hairline) || solid.contains(hairline), "the hairline sits outside the switch")
        XCTAssertTrue(solid.contains("Tokens.surface"), "solid is the surface token")
        XCTAssertTrue(bar.contains("Capsule().fill(on ? Tokens.accent : Color.clear)"), "the pill is filled accent")
        XCTAssertFalse(bar.contains("accentSoft"), "no soft accent left in the bar")
        XCTAssertFalse(bar.contains("glyphUsesRing"), "the old ring decision is gone")
        XCTAssertTrue(bar.contains(".offset(y: -AddButtonGeometry.lift)"), "the + is lifted by the geometry's lift")
        // D-219 fix wave (I-1): the + is in front of its neighbours, so a face that overflows its footprint wins the taps.
        let add = try XCTUnwrap(bar.components(separatedBy: "private var addButton: some View {").dropFirst().first?
            .components(separatedBy: "\n    }\n").first, "the addButton")
        XCTAssertEqual(count(add, ".zIndex(1)"), 1, "the + is in front of the neighbour tabs")
        let frameAt = try XCTUnwrap(add.range(of: ".frame(maxWidth: .infinity)"), "the slot frame")
        let zAt = try XCTUnwrap(add.range(of: ".zIndex(1)"), "the zIndex")
        XCTAssertTrue(frameAt.upperBound <= zAt.lowerBound, "zIndex is applied after the slot frame")
    }

    /// art.py's nudge: Vardr Labs light needs 0.75; every other set keeps the Look Book's 0.50.
    func testTheTabOutlineAlphaIsTheMeasuredOne() {
        XCTAssertEqual(TabIndicator.outlineAlpha(.vardrLight), 0.75)
        XCTAssertEqual(TabIndicator.outlineAlpha(.vardrDark), 0.5)
    }

    func testTheSeparatorFollowsTheTheme() {
        XCTAssertEqual(Separator.meta(for: .aurora), " \u{00B7} ")
        XCTAssertEqual(Separator.meta(for: .norse), " \u{16EB} ")
        XCTAssertEqual(Separator.meta(for: .vardr), " \u{00B7} ")
    }

    /// Norse colours the separator only and draws its rune in the rune font; the parts keep the line's own colour
    /// and face. Elsewhere nothing is coloured and no font is set.
    func testAStyledLineColoursOnlyTheNorseSeparator() {
        let norse = Separator.attributed(["Goa", "2 people", "INR"], size: 13, theme: .norse)
        XCTAssertEqual(String(norse.characters), "Goa \u{16EB} 2 people \u{16EB} INR")
        let coloured = norse.runs.filter { $0.foregroundColor != nil }.map { String(norse[$0.range].characters) }.joined()
        XCTAssertEqual(coloured, " \u{16EB}  \u{16EB} ")
        let runeFont = norse.runs.filter { $0.font != nil }
        XCTAssertEqual(runeFont.map { String(norse[$0.range].characters) }, ["\u{16EB}", "\u{16EB}"])
        XCTAssertTrue(runeFont.allSatisfy { $0.font == TypeRole.rune(13, relativeTo: .body) })
        let aurora = Separator.attributed(["Goa", "INR"], size: 13, theme: .aurora)
        XCTAssertEqual(String(aurora.characters), "Goa \u{00B7} INR")
        XCTAssertTrue(aurora.runs.allSatisfy { $0.foregroundColor == nil && $0.font == nil })
        XCTAssertEqual(String(Separator.attributed([], size: 13, theme: .norse).characters), "")
        // A part that itself carries the separator (a category label, "Food ᛫ Lunch") gets the rune font too.
        let nested = Separator.attributed(["Food\(Separator.meta(for: .norse))Lunch", "INR"], size: 13, theme: .norse)
        XCTAssertEqual(nested.runs.filter { $0.font != nil }.count, 2)
    }

    /// Every art colour and size the shells draw resolves (a missing key would trap at first draw).
    func testEveryArtValueIsPresent() {
        XCTAssertEqual(Set(ThemeArt.hex.keys), [
            "norseEdge", "norseCardTop", "norseCardBottom", "norseSilver", "diamondTop", "diamondBottom",
            "boltDark0", "boltDark1", "boltDark2", "boltDark3", "boltLight0", "boltLight1", "boltLight2", "boltLight3",
            "runeUnlit", "norseMeterGap", "norseCheckFill",
        ])
        for name in ThemeArt.hex.keys { _ = ThemeArt.uiColor(name) }
        // The 44 sizes and fractions (Android's parity test lists the same keys; D-219 added reticleGlyph, D-220 the
        // pill's, D-221 the ring's, the meter's, the progress dot's, the seal and the group tile).
        XCTAssertEqual(Set(ThemeArt.metrics.keys), [
            "chamfer", "frameInset", "frameLength", "frameAlpha", "gridPitch", "gridDot", "gridAlpha",
            "diamondSide", "diamondRadius", "tileRadius", "boltWidth", "boltHeight",
            "boltStartX", "boltStartY", "boltEndX", "boltEndY", "boltDark1At", "boltDark2At", "boltLight1At", "boltLight2At",
            "reticleDark", "reticleLight", "outlineLight", "outlineDark", "ornamentRune", "tabRune", "reticleGlyph",
            "pillChamfer", "pillOutline",
            "ringBox", "ringRadius", "ringStroke", "runeRingRadius", "runeRingSize", "meterBig", "meterBigVardr",
            "meterSmall", "meterSmallVardr", "meterSegment", "meterGap", "progressDot", "progressDotGap", "seal", "groupTile",
            "settingsTile", "settingsGlyph", "listPadH", "listPadV", "rowPadV", "rowGap", "themePad", "themeGap",
            "swatchHeight", "swatchRadius", "swatchRune", "swatchRuneTracking", "swatchGridPitch", "swatchGridDot",
            "swatchMarkInset", "swatchMarkLength", "modeTrack", "modeInset", "touchTarget", "segmentShadowAlpha",
            "segmentShadowRadius", "segmentShadowY", "sliderTrack", "sliderThumb", "sliderRing",
            "ledgerTile", "ledgerTileGap", "ledgerCardGap", "ledgerGridGap", "latelyAvatar", "latelyAvatarGap",
            "latelyRowGap", "emptyIcon", "groupsCardSpacing",
            "doneCheck", "doneCircleRadius", "doneCircleStroke", "doneTickStroke", "toastRadius", "toastPadV", "toastPadH",
            "toastGap", "toastDot", "toastGlyph", "toastCheckStroke", "toastInset", "toastClearOfBar", "toastShadowAlpha",
            "toastShadowRadius", "toastShadowY",
            "rowLineGap", "switchWidth", "switchHeight", "switchThumb", "switchInset", "switchBorder", "switchTravel",
            "emojiColumns", "emojiGap", "emojiGlyph", "emojiBorder", "emojiRadius", "avatarGlyph", "avatarEmoji", "lockGlyph",
            "clearGlyph",
            "heroFrameInset", "heroWatermark", "heroWatermarkTrailing", "heroWatermarkTop", "glassFilm", "glassFilmNoBlur",
            "glassRim", "glassRimLight", "glassRimDark", "glassShadowSigma", "glassShadowY", "glassShadowAlpha",
            "iconPreview", "iconPreviewRadius",
            "norseEdgeGlow", "norseEdgeGlowSigma", "norseEdgeGlowBand",
        ])
        // D-222: 25 more (You's tiles and list, the theme cards and swatches, the mode control, the slider): 69.
        // D-223: 9 more (the ledger grid and card, Lately, the empty state, the Groups card): 78.
        // D-224: 16 more (the done check, the toast and where it sits): 94.
        // D-225: 16 more (the list row's line gap, the switch, the emoji grid, the placeholders, the lock, the cross): 110.
        // D-226: 12 more (the Norse hero's frame and watermark, Aurora's glass): 122.
        // D-216: 2 more (the App icon rows' preview tile: its side and corner ratio): 124.
        // D-243 (T7): 3 more (Norse's card-edge glow: its peak, sigma and band): 127.
        XCTAssertEqual(ThemeArt.metrics.count, 127)
        XCTAssertEqual(ThemeArt.m("diamondSide"), 64, "D-219 (L17): the Look Book's diamond is the circle's square, turned")
        XCTAssertEqual(ThemeArt.boltStops(dark: true).map(\.location), [0, 0.38, 0.62, 1])
        XCTAssertEqual(ThemeArt.boltStops(dark: false).map(\.location), [0, 0.45, 0.70, 1])
        XCTAssertEqual(ThemeArt.reticleAlpha(dark: true), 0.18)
        XCTAssertEqual(ThemeArt.reticleAlpha(dark: false), 0.25)
        XCTAssertEqual(ThemeArt.m("chamfer"), 10, "D-226: 8 x 1.28")
        XCTAssertEqual(ThemeArt.m("gridAlpha"), 0.2025, accuracy: 1e-9)
        XCTAssertEqual(ThemeArt.m("pillChamfer"), 5, "D-226: C2's rule (art.py section 13) on the scaled chamfer, 10 x 26 / 52")
        XCTAssertEqual(ThemeArt.m("pillOutline"), 0.4, accuracy: 1e-9, "D-220: the Look Book's 40 percent border")
    }

    // ---- D-220 Task 1: the pill, meter, card and panel decisions; tiles; blue; disabled; the two Lucide drawings ----

    /// D-221 (E4): the ring, the meter, the progress dot, the seal and the group tile, and the two colours
    /// (.drafts/d221/art.py sections 18, 19, 21 and 22 print them; the Look Book's sizes scaled there).
    func testE4ArtValues() {
        let expected: [(String, CGFloat)] = [
            ("ringBox", 137), ("ringRadius", 62), ("ringStroke", 6), ("runeRingRadius", 60), ("runeRingSize", 14),
            ("meterBig", 10), ("meterBigVardr", 7), ("meterSmall", 6), ("meterSmallVardr", 4), ("meterSegment", 11),
            ("meterGap", 2), ("progressDot", 7), ("progressDotGap", 7), ("seal", 44), ("groupTile", 48),
        ]
        XCTAssertEqual(expected.count, 15)
        for (name, value) in expected { XCTAssertEqual(ThemeArt.metrics[name].map { CGFloat($0) }, value, name) }
        XCTAssertEqual(ThemeArt.hex["runeUnlit"], "#4A4740", "art.py 18: the unlit runes")
        XCTAssertEqual(ThemeArt.hex["norseMeterGap"], "#1A1A1C", "art.py 19: the Norse meter's gaps")
    }

    /// D-222 (S1): You's icon tiles and list rhythm, the theme cards and swatches, the mode control and the slider
    /// (.drafts/d222/art.py sections 24 to 28 print them; the Look Book's sizes scaled by 1.28 there).
    func testS1ArtValues() {
        let expected: [(String, CGFloat)] = [
            ("settingsTile", 33), ("settingsGlyph", 19), ("listPadH", 15), ("listPadV", 3), ("rowPadV", 10), ("rowGap", 13),
            ("themePad", 8), ("themeGap", 6), ("swatchHeight", 59), ("swatchRadius", 10), ("swatchRune", 20),
            ("swatchRuneTracking", 0.12), ("swatchGridPitch", 8), ("swatchGridDot", 0.9), ("swatchMarkInset", 6),
            ("swatchMarkLength", 10), ("modeTrack", 40), ("modeInset", 4), ("touchTarget", 48), ("segmentShadowAlpha", 0.15),
            ("segmentShadowRadius", 2), ("segmentShadowY", 1), ("sliderTrack", 5), ("sliderThumb", 20), ("sliderRing", 2.5),
        ]
        XCTAssertEqual(expected.count, 25)
        for (name, value) in expected {
            XCTAssertEqual(ThemeArt.metrics[name].map { CGFloat($0) } ?? -1, value, accuracy: 1e-9, name)
        }
        // The touch row holds the track with the inset on each side (art.py 26: 48 = 40 + 2 x 4).
        XCTAssertEqual((ThemeArt.metrics["modeTrack"] ?? 0) + 2 * (ThemeArt.metrics["modeInset"] ?? 0), ThemeArt.metrics["touchTarget"] ?? -1)
    }

    /// D-223 (Home): the ledger grid and card, Lately's rows, the empty state's icon and the Groups card's spacing
    /// (.drafts/d223/art.py sections 30, 32 and 34 print them; the Look Book's sizes scaled by 1.28 there).
    func testHomeArtValues() {
        let expected: [(String, CGFloat)] = [
            ("ledgerTile", 33), ("ledgerTileGap", 9), ("ledgerCardGap", 6), ("ledgerGridGap", 10), ("latelyAvatar", 33),
            ("latelyAvatarGap", 12), ("latelyRowGap", 11), ("emptyIcon", 44), ("groupsCardSpacing", 12),
        ]
        XCTAssertEqual(expected.count, 9)
        for (name, value) in expected {
            XCTAssertEqual(ThemeArt.metrics[name].map { CGFloat($0) } ?? -1, value, accuracy: 1e-9, name)
        }
    }

    /// D-224 (E3): the big done check, the small toast and where it sits, the Norse check's fill and the tick
    /// (.drafts/d224/art.py sections 37 to 39 print them).
    func testE3ArtValues() {
        let expected: [(String, CGFloat)] = [
            ("doneCheck", 104), ("doneCircleRadius", 48), ("doneCircleStroke", 3), ("doneTickStroke", 6), ("toastRadius", 18),
            ("toastPadV", 12), ("toastPadH", 15), ("toastGap", 10), ("toastDot", 26), ("toastGlyph", 15),
            ("toastCheckStroke", 3.5), ("toastInset", 16), ("toastClearOfBar", 104), ("toastShadowAlpha", 0.14),
            ("toastShadowRadius", 9), ("toastShadowY", 6),
        ]
        XCTAssertEqual(expected.count, 16)
        for (name, value) in expected {
            XCTAssertEqual(ThemeArt.metrics[name].map { CGFloat($0) } ?? -1, value, accuracy: 1e-9, name)
        }
        XCTAssertEqual(ThemeArt.hex["norseCheckFill"], "#18120A", "art.py 37: the Norse check's dark gold well (D-242)")
        XCTAssertEqual(DoneCheck.tick.count, 3)
        for (i, point) in [(32.0, 54.0), (46.0, 67.0), (73.0, 39.0)].enumerated() {
            XCTAssertEqual(DoneCheck.tick[i].0, point.0, "tick \(i) x")
            XCTAssertEqual(DoneCheck.tick[i].1, point.1, "tick \(i) y")
        }
        // The check sits in the ring's slot inside D-221's box; the circle fits the check's box with its stroke.
        XCTAssertLessThanOrEqual(ThemeArt.m("doneCheck"), ThemeArt.m("ringBox"))
        XCTAssertLessThanOrEqual(2 * ThemeArt.m("doneCircleRadius") + ThemeArt.m("doneCircleStroke"), ThemeArt.m("doneCheck"))
    }

    /// D-225 (S2): the list row's line gap, the drawn switch, the emoji grid, the picture's placeholder ratios, the lock
    /// screen's glyph and the clear cross (.drafts/d225/art.py sections 41 to 45 print them).
    /// D-226 (A, .drafts/d226/art.py sections 46, 51 and 53; the Global Constraints): the nine art values scaled x 1.28
    /// and the twelve new keys (the Norse hero's frame and watermark, Aurora's glass), retyped from art.out.
    func testArtScaleValues() {
        let changed: [(String, CGFloat)] = [
            ("chamfer", 10), ("frameInset", 4), ("frameLength", 12), ("gridPitch", 18), ("gridDot", 0.77),
            ("diamondRadius", 8), ("tileRadius", 3), ("ornamentRune", 17), ("pillChamfer", 5),
        ]
        let added: [(String, CGFloat)] = [
            ("heroFrameInset", 6), ("heroWatermark", 118), ("heroWatermarkTrailing", 13), ("heroWatermarkTop", -8),
            ("glassFilm", 0.78), ("glassFilmNoBlur", 0.82), ("glassRim", 0.35), ("glassRimLight", 0.35), ("glassRimDark", 0.35),
            ("glassShadowSigma", 12), ("glassShadowY", 4), ("glassShadowAlpha", 0.06),
        ]
        XCTAssertEqual(changed.count, 9)
        XCTAssertEqual(added.count, 12)
        for (name, value) in changed + added {
            XCTAssertEqual(ThemeArt.metrics[name].map { CGFloat($0) } ?? -1, value, accuracy: 1e-9, name)
        }
        // The panel rule's two numbers and the ornament's row follow the chamfer and the rune (art.py 46).
        XCTAssertEqual(ThemeArt.panelTallerThan, 50)
        XCTAssertEqual(ThemeArt.panelSmallChamfer, 8)
        XCTAssertEqual(TitleOrnament.rowHeight, 20)
        XCTAssertEqual(TitleOrnament.gapUnderTitle, 8, "the gap under the title stays")
        XCTAssertEqual(TitleOrnament.ruleFraction, 0.42)
    }

    func testS2ArtValues() {
        let expected: [(String, CGFloat)] = [
            ("rowLineGap", 2), ("switchWidth", 51), ("switchHeight", 31), ("switchThumb", 27), ("switchInset", 2),
            ("switchBorder", 1.5), ("switchTravel", 0.2), ("emojiColumns", 6), ("emojiGap", 8), ("emojiGlyph", 24),
            ("emojiBorder", 2), ("emojiRadius", 8), ("avatarGlyph", 0.5), ("avatarEmoji", 0.55), ("lockGlyph", 40),
            ("clearGlyph", 18),
        ]
        XCTAssertEqual(expected.count, 16)
        for (name, value) in expected {
            XCTAssertEqual(ThemeArt.metrics[name].map { CGFloat($0) } ?? -1, value, accuracy: 1e-9, name)
        }
        // The thumb fits the track with the inset on each side (art.py 42: 31 = 27 + 2 x 2).
        XCTAssertEqual(ThemeArt.m("switchThumb") + 2 * ThemeArt.m("switchInset"), ThemeArt.m("switchHeight"))
    }

    /// D-225 (L-D217-9, art.py 42): the drawn switch's paint as token names (Android's ThemeArtTest pins the same table)
    /// and where its thumb sits. The negative: OFF carries a border and ON none, so the state never rests on colour alone.
    func testTheSwitchPaintAndGeometry() {
        XCTAssertEqual(SwitchPaint.of(on: true), SwitchPaint(track: "accent", border: nil, thumb: "surface"))
        XCTAssertEqual(SwitchPaint.of(on: false), SwitchPaint(track: "ringTrack", border: "textFaint", thumb: "textDim"))
        XCTAssertNotNil(SwitchPaint.of(on: false).border, "off has a border")
        XCTAssertNil(SwitchPaint.of(on: true).border, "on has none")
        XCTAssertNotEqual(SwitchPaint.of(on: true).thumb, SwitchPaint.of(on: false).thumb)
        for paint in [SwitchPaint.of(on: true), SwitchPaint.of(on: false)] {
            for name in [paint.track, paint.thumb] + (paint.border.map { [$0] } ?? []) {
                XCTAssertNotNil(Tokens.palette[name], "\(name) is a token")
            }
        }
        XCTAssertEqual(SwitchGeometry.thumbCentreX(on: false), 15.5, accuracy: 1e-9)
        XCTAssertEqual(SwitchGeometry.thumbCentreX(on: true), 35.5, accuracy: 1e-9)
        XCTAssertEqual(SwitchGeometry.thumbCentreX(on: true) + SwitchGeometry.thumbCentreX(on: false), ThemeArt.m("switchWidth"),
                       accuracy: 1e-9, "the two places mirror each other in the track")
    }

    /// D-225 (Design 3, L-D217-9): the switch is drawn from the decisions: `SwitchPaint`'s tokens, the thumb at
    /// `SwitchGeometry.thumbCentreX`, the track in the theme's pill shape, the thumb in the person tile's shape with the
    /// segment's shadow, the switch metrics, `ButtonLook.disabledAlpha` when disabled, and a slide only when D-214's still
    /// rule lets the art move (scoped to the thumb, never `withAnimation`). It is hidden from VoiceOver: the `.toggle` ROW
    /// carries a real Toggle as its accessibility representation.
    func testTheSwitchIsDrawnAndAccessible() throws {
        let rows = try appSource("ListRows.swift")
        let drawn = try slice(rows, from: "struct ThemeSwitch: View {", to: "\n}\n")
        for needle in [
            "let paint = SwitchPaint.of(on: isOn)", "let track = ThemeArt.pillShape(theme)",
            "TileClip.shape(TileShape.of(theme, person: true), size: thumb)", "ThemeArt.m(\"switchWidth\")",
            "ThemeArt.m(\"switchHeight\")", "ThemeArt.m(\"switchThumb\")", "ThemeArt.m(\"switchBorder\")",
            "ThemeArt.m(\"switchTravel\")", "ThemeArt.m(\"segmentShadowAlpha\")", "ThemeArt.m(\"segmentShadowRadius\")",
            "ThemeArt.m(\"segmentShadowY\")", ".opacity(enabled ? 1 : ButtonLook.disabledAlpha)",
            "@Environment(\\.motionReduced) private var reduceMotion",
            "MotionClock.momentTicks(reduceMotion: reduceMotion, active: true)",
            // D-225 follow-up (R1): only the thumb's place animates (`SwitchThumb`'s animatable place; its fill takes no
            // animation), so its paint snaps; testTheSwitchThumbSlides measures it drawn.
            ".animation(moves ? .easeInOut(duration: ThemeArt.m(\"switchTravel\")) : nil, value: isOn)",
            "SwitchThumb(x: SwitchGeometry.thumbCentreX(on: isOn) - thumb / 2, y: (height - thumb) / 2, side: thumb,",
            "knob: knob, paint: Self.ink(paint.thumb))",
            "track.fill(Self.ink(paint.track))", "if let edge = paint.border {",
            ".accessibilityHidden(true)",
        ] {
            XCTAssertTrue(drawn.contains(needle), "ThemeSwitch: \(needle)")
        }
        for banned in ["withAnimation", "repeatForever", "Toggle(", "Capsule()", ".transaction", "onChange"] {
            XCTAssertFalse(drawn.contains(banned), "ThemeSwitch: \(banned)")
        }
        XCTAssertEqual(count(drawn, ".animation("), 1, "one animation, on the thumb")
        let place = try slice(rows, from: "private struct SwitchThumb: View, Animatable {", to: "\n}\n")
        for needle in ["var animatableData: CGFloat {", "get { x }", "set { x = newValue }", "knob.fill(paint)",
                       ".transaction { $0.animation = nil }\n            .offset(x: x, y: y)"] {
            XCTAssertTrue(place.contains(needle), "SwitchThumb: \(needle)")
        }
        let row = try slice(rows, from: "struct ListRow: View {", to: "\n}\n")
        let toggle = try slice(row, from: "case .toggle(let isOn, let enabled):", to: "        case ")
        for needle in [".onTapGesture { if enabled { isOn.wrappedValue.toggle() } }", ".accessibilityRepresentation {",
                       "Toggle(isOn: isOn) { Text(verbatim: spoken) }", ".disabled(!enabled)"] {
            XCTAssertTrue(toggle.contains(needle), "the .toggle row: \(needle)")
        }
        let representation = try slice(toggle, from: ".accessibilityRepresentation {", to: "\n                }\n")
        XCTAssertTrue(representation.contains("Toggle("), "the representation is a real Toggle")
        // Each paint token resolves, and the thumb never sits on a track of its own colour.
        for on in [true, false] {
            let paint = SwitchPaint.of(on: on)
            XCTAssertNotEqual(paint.track, paint.thumb)
            XCTAssertNotNil(Tokens.palette[paint.track])
        }
    }

    /// D-225 follow-up (re-look R1): the thumb SLIDES. The switch is put on screen in a window of the test host and
    /// flipped the way a row's tap flips it (a plain state change, no animation in its transaction), and the frames
    /// drawn over the next 0.6 s are read back. A frame that is neither the switch at rest before the flip nor at rest
    /// after it is the thumb on its way. The paint snaps (Android's does): on that frame the track already has its new
    /// colour and the thumb its new paint. The re-look saw the thumb jump in one frame; under Reduce Motion it must.
    @MainActor
    func testTheSwitchThumbSlides() throws {
        let moving = try switchFrames(reduceMotion: false)
        XCTAssertTrue(moving.endsMoved, "the flip moved the thumb")
        XCTAssertGreaterThanOrEqual(moving.between, 3, "the thumb passes through the track (\(moving.between) of \(moving.samples) frames)")
        XCTAssertTrue(moving.trackSnapped, "the track takes its new colour at the flip")
        XCTAssertTrue(moving.thumbSnapped, "the thumb on its way already has its new paint")
        XCTAssertTrue(moving.movesAtOnce, "the first frame after the flip already has the thumb on its way, never the new paint waiting")
        let still = try switchFrames(reduceMotion: true)
        XCTAssertTrue(still.endsMoved, "the flip moved the thumb under Reduce Motion")
        XCTAssertEqual(still.between, 0, "under Reduce Motion the thumb jumps (\(still.between) of \(still.samples) frames between)")
    }

    /// One drawn frame of the probe, at 2 px a point.
    private struct SwitchShot {
        let bytes: [UInt8]
        let row: Int

        /// The pixel at a point of the probe.
        func pixel(_ x: CGFloat, _ y: CGFloat) -> [Int] {
            let at = Int(y * 2) * row + Int(x * 2) * 4
            return bytes[at ..< at + 4].map(Int.init)
        }

        func same(_ a: [Int], _ b: [Int], within: Int = 12) -> Bool { zip(a, b).allSatisfy { abs($0 - $1) <= within } }

        func differs(_ other: SwitchShot) -> Bool {
            guard bytes.count == other.bytes.count else { return true }
            return zip(bytes, other.bytes).reduce(0) { $0 + (abs(Int($1.0) - Int($1.1)) > 24 ? 1 : 0) } > 40
        }
    }

    /// The switch on screen at (8, 8) in its probe, flipped off to on: how many sampled frames sit between its two
    /// rests, and whether the paint had already snapped on the first of them.
    @MainActor
    private func switchFrames(reduceMotion: Bool) throws
        -> (between: Int, samples: Int, endsMoved: Bool, trackSnapped: Bool, thumbSnapped: Bool, movesAtOnce: Bool) {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 120, height: 80)
        let state = SwitchProbeState()
        let host = UIHostingController(rootView: SwitchProbe(state: state, reduceMotion: reduceMotion))
        window.rootViewController = host
        window.isHidden = false // never made key: the test host's own window keeps the focus
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        func settle(_ seconds: TimeInterval) { RunLoop.main.run(until: Date().addingTimeInterval(seconds)) }
        func shot() -> SwitchShot {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 2
            let image = UIGraphicsImageRenderer(bounds: host.view.bounds, format: format).image {
                host.view.layer.render(in: $0.cgContext)
            }
            guard let cg = image.cgImage, let data = cg.dataProvider?.data else { return SwitchShot(bytes: [], row: 0) }
            return SwitchShot(bytes: Array(UnsafeBufferPointer(start: CFDataGetBytePtr(data), count: CFDataGetLength(data))),
                              row: cg.bytesPerRow)
        }
        settle(0.6)
        let start = shot()
        state.on = true
        var frames: [SwitchShot] = []
        let flipped = Date()
        while Date().timeIntervalSince(flipped) < 0.6 {
            settle(0.008)
            frames.append(shot())
        }
        settle(0.4)
        let end = shot()
        let between = frames.filter { $0.differs(start) && $0.differs(end) }
        // The track's colour at its top centre (the thumb never reaches it), and the thumb's new paint at its centre at
        // rest; on the way, a run of at least half the thumb's width along the middle row must already wear that paint.
        let height = ThemeArt.m("switchHeight"), thumb = ThemeArt.m("switchThumb")
        let trackAt = (8 + ThemeArt.m("switchWidth") / 2, 8 + 1.0)
        let paint = end.pixel(8 + SwitchGeometry.thumbCentreX(on: true), 8 + height / 2)
        var trackSnapped = !between.isEmpty, thumbSnapped = !between.isEmpty
        if let first = between.first {
            trackSnapped = first.same(first.pixel(trackAt.0, trackAt.1), end.pixel(trackAt.0, trackAt.1))
            var run = 0, best = 0
            for x in stride(from: CGFloat(8), to: 8 + ThemeArt.m("switchWidth"), by: 0.5) {
                run = first.same(first.pixel(x, 8 + height / 2), paint) ? run + 1 : 0
                best = max(best, run)
            }
            thumbSnapped = CGFloat(best) * 0.5 >= thumb / 2
        }
        // The first frame that shows the flip at all is already one on the way (the paint never waits for the place).
        let changed = frames.first { $0.differs(start) }
        let movesAtOnce = changed.map { $0.differs(end) } ?? false
        return (between.count, frames.count, start.differs(end), trackSnapped, thumbSnapped, movesAtOnce)
    }

    /// D-221 (Q2 a, K05, K06): Home's badge is the accent fill with onAccent; every other avatar the soft fill with
    /// accent; Norse's gold-brown edge, no edge elsewhere.
    func testAvatarFillsAndEdge() {
        XCTAssertEqual(AvatarStyle.fill(badge: true), .accent)
        XCTAssertEqual(AvatarStyle.fill(badge: false), .soft)
        XCTAssertEqual(AvatarFill.soft.groundToken, "accentSoft")
        XCTAssertEqual(AvatarFill.soft.inkToken, "accent")
        XCTAssertEqual(AvatarFill.accent.groundToken, "accent")
        XCTAssertEqual(AvatarFill.accent.inkToken, "onAccent")
        for fill in [AvatarFill.soft, .accent] { XCTAssertNotNil(Tokens.palette[fill.groundToken]); XCTAssertNotNil(Tokens.palette[fill.inkToken]) }
        XCTAssertEqual(AppTheme.allCases.map(AvatarStyle.edgeHex), [nil, "#6B5426", nil])
    }

    /// D-221: the seal takes the theme's tile clip; Vardr Labs alone leads the progress pill with a dot.
    func testSealAndDotPerTheme() {
        XCTAssertEqual(AppTheme.allCases.map(SealStyle.clip), [.circle, .diamond, .square])
        XCTAssertEqual(AppTheme.allCases.map(ProgressPill.showsDot), [false, false, true])
    }

    /// L19: Aurora's pill is a capsule, Norse's notched at 5 (the button's proportion, art.py section 13; D-226: on the
    /// scaled chamfer, 4 before), Vardr Labs' square; Aurora and Norse fill it and Vardr Labs outlines it at a 0.4 border.
    func testThePillShapePerTheme() {
        XCTAssertEqual(AppTheme.allCases.map(ThemeArt.pillKind), [.capsule, .notched(5), .square])
        XCTAssertEqual(ThemeArt.m("pillChamfer"), 5)
        XCTAssertEqual(ThemeArt.m("pillOutline"), 0.4, accuracy: 1e-9)
        XCTAssertEqual(AppTheme.allCases.map(PillLook.of), [.filled, .filled, .outlined])
        // The drawn shapes: a notched pill cuts its corner, a capsule rounds it, a square keeps it.
        let r = CGRect(x: 0, y: 0, width: 80, height: 24)
        XCTAssertFalse(ThemeArt.pillShape(.norse).path(in: r).contains(CGPoint(x: 1, y: 1)))
        XCTAssertTrue(ThemeArt.pillShape(.norse).path(in: r).contains(CGPoint(x: 4, y: 4)))
        XCTAssertFalse(ThemeArt.pillShape(.aurora).path(in: r).contains(CGPoint(x: 1, y: 1)))
        XCTAssertTrue(ThemeArt.pillShape(.vardr).path(in: r).contains(CGPoint(x: 0.5, y: 0.5)))
    }

    /// The plan's table, row by row (Android's AppearanceTest pins the same table in its own words).
    func testThePillPaintTable() {
        typealias L = PillPaint.Layer
        let t = { (n: String) in PillInk.token(n) }
        let o = Double(ThemeArt.m("pillOutline"))
        let page = L(t("bg"))
        // filled (Aurora, Norse): onPage changes nothing.
        for onPage in [false, true] {
            XCTAssertEqual(PillPaint.of(.accent, look: .filled, onPage: onPage), PillPaint(words: t("accent"), fill: L(t("accentSoft")), border: nil))
            XCTAssertEqual(PillPaint.of(.owner, look: .filled, onPage: onPage), PillPaint(words: .amberLetter, fill: L(.amberFill), border: nil))
            XCTAssertEqual(PillPaint.of(.selected, look: .filled, onPage: onPage), PillPaint(words: t("onAccent"), fill: L(t("accent")), border: nil))
            XCTAssertEqual(PillPaint.of(.unselected, look: .filled, onPage: onPage),
                           PillPaint(words: t("text"), fill: L(t("surface"), 0.8), border: L(t("hairline"))))
            XCTAssertEqual(PillPaint.of(.hero, look: .filled, onPage: onPage),
                           PillPaint(words: t("onHero"), fill: L(t("onHero"), Aurora.heroChipOpacity), border: nil))
            XCTAssertEqual(PillPaint.of(.photo, look: .filled, onPage: onPage),
                           PillPaint(words: .white, fill: L(.black, PhotoCard.chipAlpha), border: nil))
        }
        XCTAssertEqual(Aurora.heroChipOpacity, 0.12)
        XCTAssertEqual(PhotoCard.chipAlpha, 0.55)
        // outlined (Vardr Labs), on a card (onPage false): no fill.
        XCTAssertEqual(PillPaint.of(.accent, look: .outlined), PillPaint(words: t("accent"), fill: nil, border: L(t("accent"), o)))
        XCTAssertEqual(PillPaint.of(.owner, look: .outlined), PillPaint(words: .amberLetter, fill: nil, border: L(.amberLetter, o)))
        XCTAssertEqual(PillPaint.of(.selected, look: .outlined), PillPaint(words: t("accent"), fill: nil, border: L(t("accent"))))
        XCTAssertEqual(PillPaint.of(.unselected, look: .outlined), PillPaint(words: t("text"), fill: nil, border: L(t("hairline"))))
        XCTAssertEqual(PillPaint.of(.hero, look: .outlined), PillPaint(words: t("onHero"), fill: nil, border: L(t("onHero"), o)))
        // outlined on the page ground: the four fills that mask the grid dots with bg; hero never sits on the page.
        XCTAssertEqual(PillPaint.of(.accent, look: .outlined, onPage: true), PillPaint(words: t("accent"), fill: page, border: L(t("accent"), o)))
        XCTAssertEqual(PillPaint.of(.owner, look: .outlined, onPage: true), PillPaint(words: .amberLetter, fill: page, border: L(.amberLetter, o)))
        XCTAssertEqual(PillPaint.of(.selected, look: .outlined, onPage: true), PillPaint(words: t("accent"), fill: page, border: L(t("accent"))))
        XCTAssertEqual(PillPaint.of(.unselected, look: .outlined, onPage: true), PillPaint(words: t("text"), fill: page, border: L(t("hairline"))))
        XCTAssertEqual(PillPaint.of(.hero, look: .outlined, onPage: true), PillPaint.of(.hero, look: .outlined))
        // The negative: a photo pill keeps its film in every theme, outlined or not, on the page or not (white words over
        // a photo need it): no `bg` fill, no border.
        let film = PillPaint.of(.photo, look: .filled)
        for onPage in [false, true] {
            XCTAssertEqual(PillPaint.of(.photo, look: .outlined, onPage: onPage), film)
        }
        XCTAssertEqual(film.fill, L(.black, 0.55))
        XCTAssertNil(film.border)
        XCTAssertEqual(PillPaint.borderWidth, 1)
        // Every ink names something that exists: a token in the palette, or a literal.
        for tone in PillTone.allCases { for look in [PillLook.filled, .outlined] { for onPage in [false, true] {
            let p = PillPaint.of(tone, look: look, onPage: onPage)
            for ink in [p.words, p.fill?.ink, p.border?.ink].compactMap({ $0 }) {
                for set in ThemeSet.allCases { XCTAssertEqual(ink.hex(set).count, 7, "\(tone) \(look) \(ink)") }
            }
        } } }
    }

    /// L22: a meter is a capsule in Aurora and square in Norse and Vardr Labs; the legend swatch rounds 2 in Aurora only.
    func testMetersAndSwatchesPerTheme() {
        XCTAssertEqual(AppTheme.allCases.map(ThemeArt.meterKind), [.capsule, .square, .square])
        XCTAssertEqual(AppTheme.allCases.map(ThemeArt.swatchRadius), [2, 0, 0])
        let r = CGRect(x: 0, y: 0, width: 100, height: 10)
        XCTAssertFalse(ThemeArt.meterShape(.aurora).path(in: r).contains(CGPoint(x: 0.5, y: 0.5)))
        XCTAssertTrue(ThemeArt.meterShape(.vardr).path(in: r).contains(CGPoint(x: 0.5, y: 0.5)))
        XCTAssertTrue(ThemeArt.meterShape(.norse).path(in: r).contains(CGPoint(x: 0.5, y: 0.5)))
        let s = CGRect(x: 0, y: 0, width: 10, height: 10)
        XCTAssertFalse(ThemeArt.swatchShape(.aurora).path(in: s).contains(CGPoint(x: 0.2, y: 0.2)))
        XCTAssertTrue(ThemeArt.swatchShape(.vardr).path(in: s).contains(CGPoint(x: 0.2, y: 0.2)))
    }

    /// L20: a panel is square in Vardr Labs (min of the asked radius and the theme's card radius), Aurora keeps the
    /// asked 10 to 18, Norse keeps its notches (10 on a tall panel, 8 on a short one; D-226, 8 and 6 before).
    func testPanelsAreSquareInVardrLabs() {
        XCTAssertEqual(ThemeArt.panelKind(.vardr, radius: 14, height: 60), .rounded(0))
        XCTAssertEqual(ThemeArt.panelKind(.aurora, radius: 14, height: 60), .rounded(14))
        XCTAssertEqual(ThemeArt.panelKind(.norse, radius: 14, height: 60), .notched(10))
        XCTAssertEqual(ThemeArt.panelKind(.norse, radius: 14, height: 30), .notched(8))
        let r = CGRect(x: 0, y: 0, width: 100, height: 60)
        XCTAssertTrue(ThemeArt.panelShape(.vardr, radius: 14).path(in: r).contains(CGPoint(x: 0.5, y: 0.5)))
        XCTAssertFalse(ThemeArt.panelShape(.aurora, radius: 14).path(in: r).contains(CGPoint(x: 0.5, y: 0.5)))
    }

    /// L21: the card's shape as one decision, by kind: Aurora a rounded rectangle at the asked radius (else the
    /// theme's 20), Norse notched at 10 (D-226; 8 before), Vardr Labs square.
    func testTheCardShapePerTheme() {
        XCTAssertEqual(ThemeArt.cardKind(.aurora), .rounded(20))
        XCTAssertEqual(ThemeArt.cardKind(.aurora, radius: 18), .rounded(18))
        XCTAssertEqual(ThemeArt.cardKind(.norse), .notched(10))
        XCTAssertEqual(ThemeArt.cardKind(.norse, radius: 18), .notched(10))
        XCTAssertEqual(ThemeArt.cardKind(.vardr), .square)
        XCTAssertEqual(ThemeArt.cardKind(.vardr, radius: 18), .square)
        let r = CGRect(x: 0, y: 0, width: 100, height: 60)
        XCTAssertFalse(ThemeArt.cardShape(.norse).path(in: r).contains(CGPoint(x: 2, y: 2)))
        XCTAssertTrue(ThemeArt.cardShape(.vardr).path(in: r).contains(CGPoint(x: 0.5, y: 0.5)))
        XCTAssertFalse(ThemeArt.cardShape(.aurora, radius: 18).path(in: r).contains(CGPoint(x: 1, y: 1)))
    }

    /// L23: Norse's diamond is drawn at size x sqrt 2 (art.py section 15: 56 x 1.41421356 = 79.196), over a footprint
    /// of `size`; the other shapes at `size`. The initial is one factor, 0.42, on every shape.
    func testTheNorseDiamondIsTheTilesSize() throws {
        XCTAssertEqual(TileClip.drawnSide(.diamond, size: 56), 79.196, accuracy: 0.01)
        XCTAssertEqual(TileClip.drawnSide(.diamond, size: 30), 42.426, accuracy: 0.01)
        for shape in [TileShape.rounded, .circle, .square] { XCTAssertEqual(TileClip.drawnSide(shape, size: 56), 56) }
        let art = try appSource("ThemeArt.swift")
        XCTAssertEqual(count(art, "size * 0.42"), 1, "one factor")
        XCTAssertEqual(count(art, "size * 0.36"), 0, "the diamond's smaller letter is gone")
    }

    /// L-D217-4: a disabled button's opacity is one named value.
    func testDisabledAlphaIsNamed() {
        XCTAssertEqual(ButtonLook.disabledAlpha, 0.45)
    }

    /// L-D217-6: Lucide's search and check, the upstream path data (lucide-static v1.58.0, read 2026-10-16), pasted
    /// here; search's circle as its arc path (cx 11, cy 11, r 8: the same encoding `users` uses), and each parses.
    func testTheLucideSearchAndCheckAreVerbatim() throws {
        XCTAssertEqual(LucideGlyph.search.pathData, ["m21 21-4.34-4.34", "M11 3a8 8 0 1 0 0 16 8 8 0 1 0 0-16"])
        XCTAssertEqual(LucideGlyph.check.pathData, ["M20 6 9 17l-5-5"])
        for glyph in [LucideGlyph.search, .check] {
            for d in glyph.pathData { XCTAssertNoThrow(try SVGPath.parse(d), "\(glyph) \(d)") }
        }
        // The drawn bounds follow the data: check spans (4,6) to (20,17); search's circle spans x 3 to 19 and its handle
        // reaches (21,21).
        let check = LucideGlyph.check.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(check.minX, 4, accuracy: 0.001)
        XCTAssertEqual(check.maxX, 20, accuracy: 0.001)
        XCTAssertEqual(check.minY, 6, accuracy: 0.001)
        XCTAssertEqual(check.maxY, 17, accuracy: 0.001)
        let search = LucideGlyph.search.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(search.minX, 3, accuracy: 0.01)
        XCTAssertEqual(search.maxX, 21, accuracy: 0.01)
        XCTAssertEqual(search.maxY, 21, accuracy: 0.01)
    }

    // MARK: - D-220 Task 2: the iOS call sites read the decisions (source guards)

    /// The text between the first `from` and the next `to` after it (a function's or a type's body).
    private func slice(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    /// L19: every pill draws through `.themePill(` with its tone (and `onPage` where it sits on the page ground), and
    /// keeps no colour of its own just before it (the modifier sets the words' colour). No app file draws a `Capsule()`
    /// but the pill shape itself (ThemeArt.swift) and C1's Aurora tab pill (SkiptiTabBar.swift).
    func testEveryPillIsThemePill() throws {
        let sites: [(file: String, call: String)] = [
            ("OverviewScreen.swift", ".themePill(.hero)"),
            ("OverviewScreen.swift", ".themePill(.photo)"),
            ("ExpenseListView.swift", ".themePill(on ? .selected : .unselected, onPage: true)"),
            ("GroupsScreen.swift", ".themePill(.accent)"),
            ("GroupsScreen.swift", ".themePill(.photo)"),
            ("RemovalFlow.swift", "pill(RolesCopy.ownerPill, .owner)"),
            ("RemovalFlow.swift", "pill(RolesCopy.adminPill, .accent)"),
            ("AddExpenseScreen.swift", ".themePill(.accent, onPage: true)"),
            ("People.swift", ".themePill(isAdded ? .selected : .accent)"),
            ("YouProgress.swift", ".themePill(.accent, onPage: true)"),
        ]
        for site in sites {
            XCTAssertEqual(count(try appSource(site.file), site.call), 1, "\(site.file): \(site.call)")
        }
        let calls: [String: Int] = [
            "OverviewScreen.swift": 2, "ExpenseListView.swift": 1, "GroupsScreen.swift": 2, "RemovalFlow.swift": 1,
            "AddExpenseScreen.swift": 1, "People.swift": 1, "YouProgress.swift": 1,
        ]
        for (file, n) in calls {
            let text = try appSource(file)
            XCTAssertEqual(count(text, ".themePill("), n, "\(file): every pill, and no other")
            // The four lines before each call carry no colour of their own (it would override the pill's words).
            let lines = text.components(separatedBy: "\n")
            for (i, line) in lines.enumerated() where line.contains(".themePill(") {
                for before in lines[max(0, i - 4)..<i] {
                    XCTAssertFalse(before.contains(".foregroundStyle("), "\(file):\(i + 1) colours its own words")
                }
            }
        }
        XCTAssertTrue(try appSource("RemovalFlow.swift").contains(".themePill(tone)"), "RolePill passes its tone")
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }
        XCTAssertGreaterThan(files.count, 50, "the app sources were found")
        var capsules: [String] = []
        for file in files where count(try appSource(file), "Capsule()") > 0 { capsules.append(file) }
        XCTAssertEqual(Set(capsules), ["ThemeArt.swift", "SkiptiTabBar.swift"])
        // The photo pills take their film from the paint table, never a film of their own.
        for file in ["OverviewScreen.swift", "GroupsScreen.swift"] {
            XCTAssertFalse(try appSource(file).contains("PhotoCard.chipAlpha"), "\(file): a photo film of its own")
        }
        let art = try appSource("ThemeArt.swift")
        let pill = try slice(art, from: "struct ThemePill: ViewModifier {", to: "\n}\n")
        for needle in ["PillPaint.of(", "PillLook.of(", "ThemeArt.pillShape(", "PillPaint.borderWidth"] {
            XCTAssertTrue(pill.contains(needle), "ThemePill reads \(needle)")
        }
    }

    /// L19: `.themePill` paints what `PillPaint` says, in `pillShape`: rendered 80 x 24 pt at 2x and read back. Vardr
    /// Labs light's unselected chip on the page: the hairline border on the square's very edge, `bg` inside it; Norse's
    /// selected chip: the accent fill, the notch cut; Aurora's accent pill: `accentSoft`, the capsule's corner empty;
    /// Vardr Labs' hero pill: no fill, the onHero border at `pillOutline`.
    @MainActor
    func testAPillPaintsItsTableInItsShape() throws {
        let state = AppearanceState.shared
        let before = state.theme
        defer { state.theme = before }
        let size = CGSize(width: 80, height: 24)
        // The tokens resolve the theme from the trait (`appTheme` bridges it); the pill's paint and shape from the state.
        // `render` sets both and renders again only when the host app's applier clobbered the state (fix wave I5).
        func rgb(_ hex: String) -> [Int] {
            let v = Int(hex.dropFirst(), radix: 16)!
            return [(v >> 16) & 255, (v >> 8) & 255, v & 255]
        }
        func near(_ p: [Int], _ hex: String, _ what: String) {
            let want = rgb(hex)
            XCTAssertEqual(p[3], 255, "\(what): opaque \(p)")
            for i in 0..<3 { XCTAssertEqual(p[i], want[i], accuracy: 3, "\(what): \(p) against \(hex)") }
        }

        let chip = try render(.vardr, Color.clear.themePill(.unselected, onPage: true), scheme: .light, size: size)
        near(chip(0, 0), PillInk.token("hairline").hex(.vardrLight), "square: the border reaches the corner")
        near(chip(0, 24), PillInk.token("hairline").hex(.vardrLight), "the border is inside the shape")
        near(chip(80, 24), PillInk.token("bg").hex(.vardrLight), "onPage: the page colour under the words")
        let hero = try render(.vardr, Color.clear.themePill(.hero), scheme: .light, size: size)
        XCTAssertEqual(hero(80, 24)[3], 0, "the outlined hero pill has no fill: \(hero(80, 24))")
        XCTAssertGreaterThan(hero(0, 24)[3], 60, "its border: \(hero(0, 24))")
        XCTAssertLessThan(hero(0, 24)[3], 160, "at pillOutline, not solid: \(hero(0, 24))")

        let selected = try render(.norse, Color.clear.themePill(.selected), scheme: .dark, size: size)
        near(selected(80, 24), PillInk.token("accent").hex(.norse), "the selected fill")
        XCTAssertEqual(selected(1, 1)[3], 0, "the 4 pt notch is cut: \(selected(1, 1))")

        let accent = try render(.aurora, Color.clear.themePill(.accent), scheme: .light, size: size)
        near(accent(80, 24), PillInk.token("accentSoft").hex(.auroraLight), "the accent pill's soft fill")
        XCTAssertEqual(accent(1, 1)[3], 0, "the capsule's corner is empty: \(accent(1, 1))")
    }

    /// L21: a photo takes its card's frame: the card's shape (`ThemeArt.cardShape`) and the theme's edge (Norse's edge,
    /// Vardr Labs' marks in white); the Groups photo card at the theme's radius, the group page's band at 18.
    func testPhotosTakeTheCardsFrame() throws {
        let groups = try slice(try appSource("GroupsScreen.swift"), from: "private func photoCard(", to: "private func plainCard(")
        XCTAssertEqual(count(groups, ".photoFrame(radius: nil)"), 1)
        XCTAssertFalse(groups.contains("cardRadius, style: .continuous))"), "the Groups photo card's own clip")
        XCTAssertFalse(groups.contains("RoundedRectangle("))
        let overview = try appSource("OverviewScreen.swift")
        let band = try slice(overview, from: "private func photoBand(", to: "private func tileRow(")
        XCTAssertEqual(count(band, ".photoFrame(radius: 18)"), 1)
        XCTAssertFalse(band.contains("RoundedRectangle("), "the band's own clip")
        XCTAssertEqual(count(overview, "RoundedRectangle(cornerRadius: 18"), 0)
        let frame = try slice(try appSource("ThemeArt.swift"), from: "struct PhotoFrame: ViewModifier {", to: "\n}\n")
        XCTAssertTrue(frame.contains("ThemeArt.cardShape(theme, radius: radius)"), "the card's shape")
        XCTAssertTrue(frame.contains("CardStyle.of(theme)"), "the card's edge per theme")
        XCTAssertTrue(frame.contains("ThemeArt.norseEdge"), "Norse's edge")
        XCTAssertTrue(frame.contains("FrameMarks(inset: ThemeArt.m(\"frameInset\"), length: ThemeArt.m(\"frameLength\"))"), "Vardr Labs' marks")
        XCTAssertTrue(frame.contains("Color.white.opacity(Double(ThemeArt.m(\"frameAlpha\")))"), "the marks in white over a photo")
    }

    /// L22: the category bar is the meter shape (a capsule in Aurora, square elsewhere); its legend swatch the swatch.
    func testTheCategoryBarIsTheMeterShape() throws {
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertEqual(count(overview, ".clipShape(ThemeArt.meterShape("), 1)
        XCTAssertEqual(count(overview, "ThemeArt.swatchShape("), 1)
        XCTAssertFalse(overview.contains(".clipShape(Capsule())"))
        XCTAssertFalse(overview.contains("RoundedRectangle(cornerRadius: 2)"))
    }

    /// L23: a tile draws its shape at `TileClip.drawnSide` (Norse's diamond at size x sqrt 2) centred on a `size`
    /// footprint, so the layout around it does not move. The emoji is half the size on every shape.
    func testTilesDrawTheDiamondAtItsSide() throws {
        let tile = try slice(try appSource("GroupsScreen.swift"), from: "struct PictureTile: View {", to: "struct GroupsScreen: View {")
        let avatar = try slice(try appSource("RemovalFlow.swift"), from: "struct PersonAvatar: View {", to: "struct RequestCardView: View {")
        for (name, body) in [("PictureTile", tile), ("PersonAvatar", avatar)] {
            XCTAssertEqual(count(body, "let side = TileClip.drawnSide(tileShape, size: size)"), 1, name)
            XCTAssertEqual(count(body, ".frame(width: side, height: side)"), 1, "\(name): the drawn shape")
            XCTAssertEqual(count(body, ".frame(width: size, height: size)"), 1, "\(name): the footprint")
            XCTAssertTrue(body.contains("TileClip.initialFont(tileShape, size: size)"), "\(name): the letter follows the size")
            let drawn = try XCTUnwrap(body.range(of: ".frame(width: side, height: side)"))
            let footprint = try XCTUnwrap(body.range(of: ".frame(width: size, height: size)"))
            XCTAssertLessThan(drawn.lowerBound, footprint.lowerBound, "\(name): the footprint frames the drawn shape")
        }
        XCTAssertTrue(tile.contains(".font(.system(size: size * 0.5))"), "the emoji, one factor")
        XCTAssertFalse(tile.contains("0.4 : 0.5"), "the diamond's smaller emoji is gone")
    }

    /// L24: Vardr Labs' hero Settle up is outlined: transparent, a 1 pt onHero border, uppercase onHero words at .08 em,
    /// square, an onHero film at the chip's opacity when pressed. Aurora and Norse keep the filled slab.
    func testVardrHeroSettleIsOutlined() throws {
        let hero = try slice(try appSource("OverviewScreen.swift"), from: "private struct HeroButtonBody: View {", to: "/// Chips that wrap")
        XCTAssertTrue(hero.contains("if ButtonLook.of(theme) == .outlined {"))
        let outlined = try slice(hero, from: "if ButtonLook.of(theme) == .outlined {", to: "} else {")
        for needle in ["strokeBorder(Tokens.onHero, lineWidth: 1)", "ButtonLook.labelCase(", "ButtonLook.tracking(",
                       ".foregroundStyle(Tokens.onHero)", "Tokens.onHero.opacity(configuration.isPressed ? Aurora.heroChipOpacity : 0)",
                       "Rectangle()"] {
            XCTAssertTrue(outlined.contains(needle), "outlined: \(needle)")
        }
        XCTAssertFalse(outlined.contains("Tokens.heroStart"), "no slab in the outlined look")
        let filled = try XCTUnwrap(hero.components(separatedBy: "} else {").dropFirst().first)
        XCTAssertTrue(filled.contains(".foregroundStyle(Tokens.heroStart)"), "the filled slab stays")
    }

    /// L25: a body-drawn detail line joins its parts through `Separator.text` (Norse's gold rune); the white lines over
    /// a photo keep the string (a white separator). The parts join to the string the tests already pin.
    func testGroupDetailLinesUseTheSeparator() throws {
        XCTAssertEqual(GroupsCopy.detailParts(people: 1, currency: "INR"), ["1 person", "INR"])
        XCTAssertEqual(OverviewCopy.settlesInParts(people: 4, currency: "INR"), ["4 people", "settles in INR"])
        XCTAssertEqual(GroupsCopy.detailParts(people: 3, currency: "EUR").joined(separator: Separator.meta),
                       GroupsCopy.detail(people: 3, currency: "EUR"))
        XCTAssertEqual(OverviewCopy.settlesInParts(people: 2, currency: "USD").joined(separator: Separator.meta),
                       OverviewCopy.settlesIn(people: 2, currency: "USD"))
        let groups = try appSource("GroupsScreen.swift")
        let plain = try slice(groups, from: "private func plainCard(", to: "private func waitingRow(")
        XCTAssertEqual(count(plain, "Separator.text(GroupsCopy.detailParts("), 1)
        XCTAssertFalse(plain.contains("Text(verbatim: GroupsCopy.detail("))
        let photo = try slice(groups, from: "private func photoCard(", to: "private func plainCard(")
        XCTAssertEqual(count(photo, "Text(verbatim: GroupsCopy.detail("), 1, "white over a photo")
        let overview = try appSource("OverviewScreen.swift")
        let row = try slice(overview, from: "private func tileRow(", to: "private func statsRow(")
        XCTAssertEqual(count(row, "Separator.text(OverviewCopy.settlesInParts("), 1)
        XCTAssertFalse(row.contains("OverviewCopy.settlesIn("))
        let band = try slice(overview, from: "private func photoBand(", to: "private func tileRow(")
        XCTAssertEqual(count(band, "Text(verbatim: OverviewCopy.settlesIn("), 1, "white over a photo")
    }

    /// L69: Delete account is a destructive action (the owe colour), as Android's `DangerAction`.
    func testDeleteAccountIsDestructive() throws {
        // D-225 (Design 4): Delete account is a danger list row on Security's careful card (owed title), not a button.
        let hub = try appSource("AccountHubScreen.swift")
        let row = try slice(hub, from: "case .deleteAccount:", to: "default:")
        XCTAssertTrue(row.contains("tone: .danger)"), row)
        XCTAssertFalse(row.contains("SecondaryButtonStyle"), row)
    }

    /// L-D217-4: every button style dims a disabled button by the one named value.
    func testDisabledButtonsUseTheNamedAlpha() throws {
        let tokens = try appSource("DesignTokens.swift")
        XCTAssertEqual(count(tokens, "ButtonLook.disabledAlpha"), 4)
        XCTAssertFalse(tokens.contains(": 0.45)"))
    }

    /// G1 fix 1: Norse's inner panels and stat tiles take the cards' notch. ONE rule sets the cut: 10 on a panel taller
    /// than 50 pt, else 8 (D-226, art.py section 46: both scaled with the chamfer; 8 above 40, else 6, before); the
    /// geometry clamps it to half the short side. Aurora and Vardr Labs keep their radius.
    func testThePanelChamferRule() {
        XCTAssertEqual(ThemeArt.panelChamfer(height: 51), 10)
        XCTAssertEqual(ThemeArt.panelChamfer(height: 50), 8)
        XCTAssertEqual(ThemeArt.panelChamfer(height: 20), 8)
        XCTAssertEqual(ThemeArt.panelChamfer(height: 120), ThemeArt.m("chamfer"))
        XCTAssertEqual(ThemeArt.panelKind(.norse, radius: 12, height: 52), .notched(10))
        XCTAssertEqual(ThemeArt.panelKind(.norse, radius: 12, height: 40), .notched(8))
        XCTAssertEqual(ThemeArt.panelKind(.aurora, radius: 12, height: 52), .rounded(12))
        XCTAssertEqual(ThemeArt.panelKind(.vardr, radius: 18, height: 20), .rounded(0), "D-220 (L20): square in Vardr Labs")
        // The drawn path follows the rule: (4.5, 4.5) is cut off by a 10 pt chamfer (x + y = 9 < 10), kept by an 8 pt
        // one (9 > 8), and (3, 3) is cut by the 8 (6 < 8).
        let tall = ThemeArt.panelShape(.norse, radius: 12).path(in: CGRect(x: 0, y: 0, width: 100, height: 60))
        let short = ThemeArt.panelShape(.norse, radius: 12).path(in: CGRect(x: 0, y: 0, width: 100, height: 30))
        XCTAssertFalse(tall.contains(CGPoint(x: 4.5, y: 4.5)))
        XCTAssertTrue(short.contains(CGPoint(x: 4.5, y: 4.5)))
        XCTAssertFalse(short.contains(CGPoint(x: 3, y: 3)))
        // The negative: an 8 pt chamfer on a 10 pt panel is clamped to 5 (half the short side).
        let tiny = ThemeArt.panelShape(.norse, radius: 12).path(in: CGRect(x: 0, y: 0, width: 100, height: 10))
        XCTAssertFalse(tiny.contains(CGPoint(x: 2.4, y: 2.4)))
        XCTAssertTrue(tiny.contains(CGPoint(x: 2.6, y: 2.6)))
        // Outside Norse the rounded rectangle is the call site's own.
        let aurora = ThemeArt.panelShape(.aurora, radius: 12).path(in: CGRect(x: 0, y: 0, width: 100, height: 60))
        XCTAssertFalse(aurora.contains(CGPoint(x: 2, y: 2)))
        XCTAssertTrue(aurora.contains(CGPoint(x: 6, y: 6)))
    }

    /// G1 fix 1: every inner panel and stat tile takes its shape from `panelShape`, so a new one cannot miss it: none
    /// of these files draws a rounded rectangle of 8 to 18 pt of its own, and each names `panelShape` the exact number
    /// of times. (Cards, the photo header's clip, the theme previews, the emoji cells and the pills are not panels.)
    func testEveryPanelTakesTheShapeFromTheOneDecision() throws {
        let sites: [(file: String, count: Int)] = [
            // D-225: About's panels are glass cards.
            // D-248 (U30.4): the Expenses list's Undo foot panel went (the delete's Undo is the toast).
            ("About.swift", 0), ("ExpenseDetailView.swift", 4), ("ExpenseListView.swift", 0), ("SettleScreen.swift", 1),
            ("GroupSettings.swift", 2), ("RemovalFlow.swift", 2), ("SettleAll.swift", 1), ("OverviewScreen.swift", 1),
        ]
        for site in sites {
            let text = try appSource(site.file)
            XCTAssertEqual(text.components(separatedBy: "ThemeArt.panelShape(").count - 1, site.count, site.file)
            XCTAssertNil(text.range(of: #"\.rect\(cornerRadius: \d"#, options: .regularExpression), "\(site.file): a rect of its own")
            if site.file != "OverviewScreen.swift" {
                XCTAssertNil(text.range(of: #"RoundedRectangle\(cornerRadius: (8|10|12|14|16|18)\b"#, options: .regularExpression), "\(site.file): a rounded rectangle of its own")
            }
        }
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertFalse(overview.contains("RoundedRectangle(cornerRadius: 14)"), "the stat tile")
        // D-226 fix wave (M9, D28's spirit): the expense detail's choice panel draws its hairline 1 pt INSIDE the panel,
        // as Android's `.border(1.dp, ...)` does (a stroke twice the width, clipped: `panelShape` cannot inset), never
        // straddling its edge.
        let detail = try appSource("ExpenseDetailView.swift")
        let inside = "ThemeArt.panelShape(AppearanceState.shared.theme, radius: 10).stroke(Tokens.hairline, lineWidth: 2)"
            + ".clipShape(ThemeArt.panelShape(AppearanceState.shared.theme, radius: 10))"
        XCTAssertEqual(detail.components(separatedBy: inside).count - 1, 1, "the hairline inside the panel")
        XCTAssertFalse(detail.contains(".stroke(Tokens.hairline, lineWidth: 1))"), "the straddling hairline")
    }

    /// G1 (the founder's look: "the entire tile has so much space"): Norse's tab runes are 34 pt. Vardr Runes Carved
    /// draws its ink in 0.70 em (cap height 700/1000), so the ink stands 23.8 pt inside the 30 pt slot; the line box
    /// (ascent 900 + descent 250 per 1000) is 39.1 pt and overflows the slot, so the slot stays 30 pt (the label, the
    /// underline and the + keep their places) and the rune is centred by its ink: the ink middle sits 350 above the
    /// baseline, the line box middle 325, so it moves down 25/1000 em (0.85 pt). The rune does not scale with text size.
    func testTheTabRunesFillTheirSlotByTheirInk() throws {
        XCTAssertEqual(ThemeArt.m("tabRune"), 34)
        XCTAssertEqual(ThemeArt.m("tabRune") * 0.70, 23.8, accuracy: 0.0001)
        XCTAssertLessThan(ThemeArt.m("tabRune") * 0.70, 30)
        XCTAssertEqual(ThemeArt.m("tabRune") * 1.15, 39.1, accuracy: 0.0001)
        XCTAssertEqual(ThemeArt.runeInkShiftEm, 0.025, accuracy: 1e-9)
        XCTAssertEqual(ThemeArt.tabRuneInkShift, 0.85, accuracy: 1e-6)
        let bar = try appSource("SkiptiTabBar.swift")
        XCTAssertEqual(bar.components(separatedBy: ".offset(y: ThemeArt.tabRuneInkShift)").count - 1, 1)
        XCTAssertEqual(bar.components(separatedBy: ".frame(width: 52, height: 30)").count - 1, 1, "the slot stays 52 x 30")
        // Fixed at every text size (the rune role scales only on request).
        XCTAssertEqual(TypeRole.runeUIFont(34, relativeTo: nil, category: .accessibilityExtraExtraExtraLarge).pointSize, 34)
    }

    /// G1: ONE decision names the shape of every button and input: Norse notches it like its cards (chamfer 10 since
    /// D-226, 8 before); Aurora and Vardr Labs keep the rounded rectangle they had (a call site may name its own radius).
    func testTheControlShapeIsNotchedInNorseOnly() {
        XCTAssertEqual(ThemeArt.controlKind(.norse), .notched(10))
        XCTAssertEqual(ThemeArt.controlKind(.norse, radius: 10), .notched(10))
        XCTAssertEqual(ThemeArt.controlKind(.aurora), .rounded(14))
        XCTAssertEqual(ThemeArt.controlKind(.vardr), .rounded(0))
        XCTAssertEqual(ThemeArt.controlKind(.aurora, radius: 10), .rounded(10))
        XCTAssertEqual(ThemeArt.controlKind(.vardr, radius: 10), .rounded(10))
        XCTAssertEqual(ThemeArt.controlKind(.norse), .notched(ThemeArt.m("chamfer")))
        // The drawn shape follows the decision: Norse's path is the card's notched octagon (a corner is cut off).
        let box = CGRect(x: 0, y: 0, width: 200, height: 52)
        let notched = ThemeArt.controlShape(.norse).path(in: box)
        XCTAssertFalse(notched.contains(CGPoint(x: 3, y: 3)), "Norse's corner is cut")
        XCTAssertTrue(notched.contains(CGPoint(x: 5, y: 5)))
        XCTAssertTrue(notched.contains(CGPoint(x: 100, y: 26)))
        XCTAssertEqual(notched.boundingRect, box)
        let rounded = ThemeArt.controlShape(.aurora).path(in: box)
        XCTAssertTrue(rounded.contains(CGPoint(x: 6, y: 6)), "Aurora's corner is rounded by 14, not cut by 8")
        XCTAssertFalse(rounded.contains(CGPoint(x: 1, y: 1)))
    }

    /// G1: every button and input background asks for the shape from the one decision, so a new button cannot miss
    /// it: no site draws a `buttonRadius` rounded rectangle of its own.
    func testEveryControlTakesTheShapeFromTheOneDecision() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path).filter({ $0.hasSuffix(".swift") })
        where !["Appearance.swift", "ThemeArt.swift"].contains(file) {
            let text = try appSource(file)
            XCTAssertFalse(text.contains("buttonRadius"), "\(file) draws a button radius of its own")
        }
        let sites: [(file: String, count: Int)] = [("DesignTokens.swift", 3), ("OverviewScreen.swift", 1), ("Invites.swift", 1)]
        for site in sites {
            let text = try appSource(site.file)
            XCTAssertEqual(text.components(separatedBy: "ThemeArt.controlShape(").count - 1, site.count, "\(site.file)")
        }
    }

    /// The outlined button's tracking is .08 em of the size it draws at (17 pt at Large: 1.36).
    func testTheOutlinedButtonsTracking() {
        let state = AppearanceState.shared
        let (step, phone) = (state.textStep, state.phoneCategory)
        defer { state.textStep = step; state.phoneCategory = phone }
        state.phoneCategory = .large
        state.textStep = 0
        XCTAssertEqual(ButtonLook.tracking(17), 17 * 0.08, accuracy: 0.0001)
    }

    /// F4: Vardr Labs uppercases a button's words, except an identifier that keeps its case (an @username
    /// suggestion: handles are lowercase); the other themes never change the case.
    func testOnlyVardrLabsButtonsUppercaseAndAnIdentifierKeepsItsCase() throws {
        XCTAssertEqual(ButtonLook.labelCase(.vardr, keepsCase: false), .uppercase)
        XCTAssertNil(ButtonLook.labelCase(.vardr, keepsCase: true))
        for theme in [AppTheme.aurora, .norse] {
            XCTAssertNil(ButtonLook.labelCase(theme, keepsCase: false), "\(theme)")
            XCTAssertNil(ButtonLook.labelCase(theme, keepsCase: true), "\(theme)")
        }
        XCTAssertTrue(try appSource("UsernameStep.swift").contains("SecondaryButtonStyle(keepsCase: true)"), "the @username chips")
        let tokens = try appSource("DesignTokens.swift")
        XCTAssertFalse(tokens.contains(".textCase(.uppercase)"), "a button style uppercases around ButtonLook.labelCase")
        XCTAssertEqual(tokens.components(separatedBy: ".textCase(ButtonLook.labelCase(").count - 1, 2, "both outlined bodies")
    }

    /// D-220 fix wave (I5): [view] rendered with the shared state's theme (and the `appTheme` trait) set to [theme]. The
    /// host app's applier (`AppearanceApplier.sync`) writes the stored theme into the same shared state on its own
    /// schedule (first frames, a scheme or text-size notification), and once landed between a set and a render. So: set,
    /// render, check the theme held, and render again only when it did not, up to 3 tries. A wrong pixel is never
    /// retried: the caller asserts on the one render that ran with its theme throughout. (The bar tests make their own
    /// `AppearanceState()`, which the host never writes.)
    @MainActor
    private func render(_ theme: AppTheme, _ view: some View, scheme: ColorScheme, size: CGSize) throws -> (Int, Int) -> [Int] {
        let state = AppearanceState.shared
        for _ in 0..<3 {
            state.theme = theme
            let read = try pixels(view.environment(\.appTheme, theme).environment(\.colorScheme, scheme), size: size, scale: 2)
            if state.theme == theme { return read }
        }
        throw NSError(domain: "ThemeArtTests", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "the host app reset the theme during three renders of \(theme) in a row"])
    }

    /// One pixel of a rendered view, as RGBA bytes (0 to 255).
    @MainActor
    private func pixels(_ view: some View, size: CGSize, scale: CGFloat) throws -> (Int, Int) -> [Int] {
        let renderer = ImageRenderer(content: view.frame(width: size.width, height: size.height))
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
        let copy = bytes
        return { x, y in
            guard x >= 0, y >= 0, x < w, y < h, drawn else { return [-1, -1, -1, -1] }
            let i = (y * w + x) * 4
            return (0..<4).map { Int(copy[i + $0]) }
        }
    }

    /// F3: a tinted card keeps its theme's art on top: the tint follows the card's own shape (Norse's notches
    /// stay cut, Aurora's corners stay round) and sits under the strokes (Norse's edge, Vardr Labs' frame
    /// marks). The tint is an opaque red so any art drawn under it would vanish. 200 x 100 pt at 2x.
    @MainActor
    func testATintedCardKeepsItsThemesArtOnTop() throws {
        let state = AppearanceState.shared
        let before = state.theme
        defer { state.theme = before }
        let card = Color.clear.glassCard(padding: 0, tint: Color(red: 1, green: 0, blue: 0))
        let size = CGSize(width: 200, height: 100)
        func isRed(_ p: [Int]) -> Bool { p[3] > 200 && p[0] > 200 && p[1] < 60 && p[2] < 60 }

        let norse = try render(.norse, card, scheme: .dark, size: size)
        XCTAssertTrue(isRed(norse(200, 100)), "the tint fills the card: \(norse(200, 100))")
        // D-243 (T7): the notch stays cut: the card's fill never reaches the corner; only the edge glow outside it does
        // (the pixel at (1, 1) pt is 5.66 pt off the cut, in the faintest band: alpha 0.005, 1.3 of 255; at most 3 of
        // 255 allows its antialiasing), and it is not the tint.
        XCTAssertLessThanOrEqual(norse(2, 2)[3], 3, "the notch stays cut: \(norse(2, 2))")
        XCTAssertFalse(isRed(norse(2, 2)), "the notch stays cut: \(norse(2, 2))")
        XCTAssertFalse(isRed(norse(200, 0)), "the edge draws over the tint: \(norse(200, 0))")

        let vardr = try render(.vardr, card, scheme: .light, size: size)
        XCTAssertTrue(isRed(vardr(14, 40)), "the tint fills the card: \(vardr(14, 40))")
        // The top-left frame mark runs along y = 4 pt from x = 4 to 16 pt: pixel row 8 at 2x (D-226: frameInset 4 and
        // frameLength 12; row 6, y = 3, before).
        XCTAssertFalse(isRed(vardr(14, 8)), "the frame mark draws over the tint: \(vardr(14, 8))")

        let aurora = try render(.aurora, card, scheme: .light, size: size)
        XCTAssertTrue(isRed(aurora(200, 100)), "the tint fills the card: \(aurora(200, 100))")
        XCTAssertLessThan(aurora(0, 0)[3], 40, "Aurora's corner stays round: \(aurora(0, 0))")
        // D-221 (Q16 a, the founder): the group header is bare now, so no screen passes a tone as a card's tint.
        XCTAssertFalse(try appSource("OverviewScreen.swift").contains(".glassCard(padding: 0, tint:"), "the group header is bare")
    }

    private func count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

    /// The source of `AddButtonFace` alone (its struct, up to the next MARK).
    private func addButtonFaceSource() throws -> String {
        try XCTUnwrap(try appSource("ThemeArt.swift").components(separatedBy: "struct AddButtonFace: View {").dropFirst().first?
            .components(separatedBy: "// MARK: - D-213 words").first, "AddButtonFace")
    }

    /// One `case` of `AddButtonFace`'s switch (from `case .x:` to the next case or the end of the body).
    private func addButtonCase(_ name: String) throws -> String {
        let face = try addButtonFaceSource()
        let after = try XCTUnwrap(face.components(separatedBy: "case .\(name):").dropFirst().first, "case .\(name)")
        return try XCTUnwrap(after.components(separatedBy: "        case .").first?.components(separatedBy: "\n    }\n").first)
    }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The shared components read the per-theme decisions (a component that skips one keeps the old look).
    func testTheSharedComponentsReadTheDecisions() throws {
        let bar = try appSource("SkiptiTabBar.swift")
        XCTAssertTrue(bar.contains("TabIndicator.of("), "tab bar: active mark")
        XCTAssertTrue(bar.contains("TabIndicator.drawsRunes("), "tab bar: rune icons")
        XCTAssertTrue(bar.contains("AddButtonFace("), "tab bar: the +")
        let tokens = try appSource("DesignTokens.swift")
        XCTAssertTrue(tokens.contains("CardStyle.of("), "glassCard")
        XCTAssertTrue(try appSource("ThemeMotion.swift").contains("Ground.of("), "auroraBackground (D-214: PageArt draws the ground)")
        XCTAssertTrue(tokens.contains("ButtonLook.of("), "button styles")
        XCTAssertTrue(try appSource("GroupsScreen.swift").contains("TileShape.of("), "PictureTile")
        XCTAssertTrue(try appSource("RemovalFlow.swift").contains("TileShape.of("), "PersonAvatar")
        XCTAssertTrue(try appSource("Profile.swift").contains("AvatarStyle."), "AvatarView")
    }

    /// D-213 fix wave 2 (G2): a person's initial on a tone goes through `PersonAvatar` (it follows the theme's
    /// person shape); a tone-filled `Circle()` initial in any app file stays round beside Norse's diamonds.
    func testNoToneFilledCircleInitialRemains() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }
        XCTAssertGreaterThan(files.count, 50, "the app sources were found")
        let pattern = try NSRegularExpression(pattern: #"\.background\(\s*\w*tone\w*\.fill\s*,\s*in:\s*Circle\(\)"#)
        var offenders: [String] = []
        for file in files {
            let text = try String(contentsOf: dir.appendingPathComponent(file), encoding: .utf8)
            if pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil { offenders.append(file) }
        }
        XCTAssertEqual(offenders, [])
        XCTAssertTrue(try appSource("OverviewScreen.swift").contains("PersonAvatar(name: p.name, size: 40)"), "Balance's People rows")
        XCTAssertTrue(try appSource("SettleAll.swift").contains("PersonAvatar(name: other, size: 44)"), "Settle up cards")
    }

    /// The clip a tile takes per theme: Aurora's rounding is size x 0.3 (D-179), a person's circle; Norse a
    /// diamond; Vardr Labs square. Checked through the path's bounds and a corner point.
    func testATilesClipFollowsTheTheme() {
        let r = CGRect(x: 0, y: 0, width: 48, height: 48)
        let corner = CGPoint(x: 1, y: 1)
        XCTAssertFalse(TileClip.shape(.rounded, size: 48).path(in: r).contains(corner))
        XCTAssertFalse(TileClip.shape(.circle, size: 48).path(in: r).contains(corner))
        XCTAssertFalse(TileClip.shape(.diamond, size: 48).path(in: r).contains(CGPoint(x: 8, y: 8)))
        XCTAssertTrue(TileClip.shape(.square, size: 48).path(in: r).contains(corner))
        XCTAssertTrue(TileClip.shape(.diamond, size: 48).path(in: r).contains(CGPoint(x: 24, y: 24)))
    }

    /// The large and inline titles draw the theme's display face (Space Grotesk, or Cormorant SC in Norse), at
    /// the Look Book's weight (700; Vardr Labs 600) and tracking (-0.01 em; Norse +0.03; Vardr Labs -0.02).
    func testTitlesTakeTheThemesDisplayFace() {
        let grotesk = TitleStyle.font(large: true, theme: .aurora, category: .large)
        XCTAssertTrue(grotesk.fontName.hasPrefix("SpaceGrotesk"), grotesk.fontName)
        XCTAssertEqual(TitleStyle.font(large: true, theme: .norse, category: .large).fontName, "CormorantSC-Bold")
        XCTAssertEqual(TitleStyle.font(large: false, theme: .norse, category: .large).fontName, "CormorantSC-Bold")
        XCTAssertTrue(TitleStyle.font(large: true, theme: .vardr, category: .large).fontName.hasPrefix("SpaceGrotesk"))
        let large = TitleStyle.font(large: true, theme: .aurora, category: .large)
        let bigger = TitleStyle.font(large: true, theme: .aurora, category: .accessibilityLarge)
        XCTAssertGreaterThan(bigger.pointSize, large.pointSize, "titles follow the text size")
        for (theme, em) in [(AppTheme.aurora, -0.01), (.norse, 0.03), (.vardr, -0.02)] {
            let a = TitleStyle.attributes(large: true, theme: theme, category: .large)
            let font = a[.font] as? UIFont
            XCTAssertEqual(a[.kern] as? CGFloat ?? 0, (font?.pointSize ?? 0) * em, accuracy: 0.001, "\(theme)")
        }
    }

    /// M11: the title draws the weight the Look Book asks for (700; Vardr Labs 600) on Space Grotesk's wght
    /// axis, after the text-size scaling (Norse's Cormorant SC Bold is a static face, named above).
    func testTitlesDrawTheirWeightOnTheWghtAxis() throws {
        for (theme, weight) in [(AppTheme.aurora, 700.0), (.vardr, 600.0)] {
            for large in [true, false] {
                let font = TitleStyle.font(large: large, theme: theme, category: .large)
                let variation = try XCTUnwrap(CTFontCopyVariation(font as CTFont) as? [NSNumber: NSNumber], "\(theme) \(large)")
                XCTAssertEqual(variation[TypeRole.weightAxis]?.doubleValue ?? -1, weight, accuracy: 0.5, "\(theme) large \(large)")
            }
        }
    }

    /// W1: the app's own appearance step (`Appearance.apply`, run on every theme or text-size change) sets the
    /// title attributes on a bar that already exists; every title depends on this wiring.
    @MainActor
    func testTheAppearanceStepSetsTheTitleOnALiveBar() throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        let nav = UINavigationController(rootViewController: UIViewController())
        window.rootViewController = nav // never made key: the test host's own window stays in front
        defer { TitleStyle.apply(AppearanceState.shared, to: window) } // the proxy back to the host's theme
        let state = AppearanceState()
        state.theme = .norse
        Appearance.apply(state, to: window)
        let font = try XCTUnwrap(nav.navigationBar.largeTitleTextAttributes?[.font] as? UIFont)
        XCTAssertEqual(font.fontName, "CormorantSC-Bold")
        let inline = try XCTUnwrap(nav.navigationBar.titleTextAttributes?[.font] as? UIFont)
        XCTAssertEqual(inline.fontName, "CormorantSC-Bold")
    }

    /// Applying reaches a bar that already exists (a theme switch with screens open).
    @MainActor
    func testTitlesReachALiveNavigationBar() throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        let nav = UINavigationController(rootViewController: UIViewController())
        window.rootViewController = nav // never made key: the test host's own window stays in front
        let state = AppearanceState()
        state.theme = .norse
        TitleStyle.apply(state, to: window)
        let font = try XCTUnwrap(nav.navigationBar.largeTitleTextAttributes?[.font] as? UIFont)
        XCTAssertEqual(font.fontName, "CormorantSC-Bold")
        state.theme = .aurora
        TitleStyle.apply(state, to: window)
        let again = try XCTUnwrap(nav.navigationBar.titleTextAttributes?[.font] as? UIFont)
        XCTAssertTrue(again.fontName.hasPrefix("SpaceGrotesk"), again.fontName)
    }

    func testTheOrnamentIsNorsesAlone() {
        XCTAssertEqual(AppTheme.allCases.map(TitleOrnament.shows), [false, true, false])
    }

    /// D-217: the four tab roots carry the ornament (it hangs under their large title); You has an inline title and
    /// no ornament, and no pushed page has one.
    func testTheOrnamentSitsOnTheFourRoots() throws {
        for file in ["OverviewScreen.swift", "ExpenseListView.swift", "GroupsScreen.swift", "SettleAll.swift"] {
            XCTAssertTrue(try appSource(file).contains("TitleOrnament("), file)
        }
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }
        let users = try files.filter { try appSource($0).contains("TitleOrnament(") }.sorted()
        // D-245 (U9, the approved mock-up v2): the welcome's centred titles carry it in Norse too (a full-screen page with
        // its own large titles, not a pushed page).
        XCTAssertEqual(users, ["ExpenseListView.swift", "GroupsScreen.swift", "OverviewScreen.swift", "SettleAll.swift", "Welcome.swift"],
                       "the ornament belongs to the four tab roots and the welcome (not You)")
    }

    /// D-217 (the Look Book's `.orn`): each rule is 42% of the width, the rune takes the 16% between them, and the
    /// ornament sits 8 points under the large title.
    func testTheOrnamentsRulesAreFortyTwoPercentAndItHangsEightPointsUnderTheTitle() throws {
        XCTAssertEqual(TitleOrnament.ruleFraction, 0.42)
        XCTAssertEqual(TitleOrnament.ruleWidth(in: 358), 150.36, accuracy: 0.001)
        XCTAssertEqual(358 - 2 * TitleOrnament.ruleWidth(in: 358), 358 * 0.16, accuracy: 0.001, "the rune's gap")
        XCTAssertEqual(TitleOrnament.gapUnderTitle, 8)
        // The offset cancels the screen's own padding and the system's gap, leaving the 8 points (the roots pad 16,
        // Groups 8, an empty Expenses 0).
        XCTAssertEqual(TitleOrnament.topOffset(above: 16), -23)
        XCTAssertEqual(TitleOrnament.topOffset(above: 8), -15)
        XCTAssertEqual(TitleOrnament.topOffset(above: 0), -7)
        // Every call site, the empty and error states included, names its own padding: exact counts per `above:`
        // value, and the per-file total equals the sum, so a site with a wrong or missing `above:` goes red.
        let sites: [(file: String, above: [String: Int])] = [
            ("OverviewScreen.swift", ["16": 2]),                 // the scrolling root and Balance's empty state
            ("GroupsScreen.swift", ["8": 1]),
            ("SettleAll.swift", ["16": 1]),
            ("ExpenseListView.swift", ["16": 1, "0": 2]),        // the list, then the error and empty states
        ]
        for (file, above) in sites {
            let text = try appSource(file)
            for (padding, count) in above {
                XCTAssertEqual(text.components(separatedBy: "TitleOrnament(above: \(padding))").count - 1, count,
                               "\(file): TitleOrnament(above: \(padding))")
            }
            XCTAssertEqual(text.components(separatedBy: "TitleOrnament(").count - 1, above.values.reduce(0, +),
                           "\(file): a TitleOrnament site names no (or an unlisted) above")
        }
    }

    /// D-217: the back button's chevron is Lucide's chevron-left, drawn from its path data (not an SF Symbol), in the
    /// bar colour as an original image; the mask is the same drawing. Both are 15 points tall at the shipped scale.
    func testTheBackChevronIsLucide() throws {
        XCTAssertEqual(LucideGlyph.chevronLeft.pathData, ["m15 18-6-6 6-6"], "Lucide's own bytes (read 2026-10-15)")
        XCTAssertEqual(LucideGlyph.chevronRight.pathData, ["m9 18 6-6-6-6"], "Lucide's own bytes (read 2026-10-15)")
        let art = try appSource("ThemeArt.swift")
        let body = try XCTUnwrap(art.components(separatedBy: "static func backIndicator(").dropFirst().first?
            .components(separatedBy: "\n    }\n").first, "BarTint.backIndicator")
        XCTAssertTrue(body.contains("LucideGlyph.chevronLeft.inkImage("), "BarTint.backIndicator draws Lucide's chevron-left")
        XCTAssertFalse(body.contains("systemName"), "BarTint.backIndicator still draws an SF Symbol")
        let indicator = BarTint.backIndicator(.red)
        XCTAssertEqual(indicator.image.renderingMode, .alwaysOriginal)
        // D-219: the ink (12 units tall, 6 wide) plus the stroke (LucideStroke.width), to a device pixel.
        XCTAssertEqual(indicator.image.size.height, (12 + LucideStroke.width) * BarTint.backChevronScale, accuracy: 0.34, "the ink plus the stroke")
        XCTAssertEqual(indicator.image.size.height, 18.0, accuracy: 0.34, "golden: (12 + 2.4) x 1.25")
        XCTAssertEqual(indicator.image.size.width, (6 + LucideStroke.width) * BarTint.backChevronScale, accuracy: 0.34, "the ink plus the stroke, to a device pixel (the renderer rounds 31.5 px up to 32 at 3x)")
        XCTAssertEqual(indicator.image.size.width, 10.5, accuracy: 0.34, "golden: (6 + 2.4) x 1.25, to a device pixel")
        XCTAssertEqual(indicator.mask.size, indicator.image.size)
        // It has ink, and the ink is the colour asked for.
        let cg = try XCTUnwrap(indicator.image.cgImage)
        var rgba = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        let ctx = try XCTUnwrap(CGContext(data: &rgba, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: cg.width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        let inked = stride(from: 0, to: rgba.count, by: 4).filter { rgba[$0 + 3] > 200 }
        XCTAssertGreaterThan(inked.count, 20, "the chevron is not blank")
        XCTAssertTrue(inked.allSatisfy { rgba[$0] > 200 && rgba[$0 + 1] < 60 && rgba[$0 + 2] < 60 }, "drawn in the colour asked for")
    }

    /// D-217: a list row's chevron is Lucide's chevron-right (`RowChevron`), never the SF Symbol, in every file.
    func testRowChevronsAreLucide() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path).filter({ $0.hasSuffix(".swift") }) {
            let text = try appSource(file)
            // Every SF spelling of a row or back chevron is banned; chevron.down (BalanceScreen's disclosure) stays.
            for banned in ["chevron.right", "chevron.forward", "chevron.left", "chevron.backward"] {
                XCTAssertFalse(text.contains("\"\(banned)\""), "\(file) draws an SF Symbol \(banned)")
            }
        }
        // D-225: You's rows draw through ListRow, whose chevron, value and piece cases hold three in ListRows.swift (D-234:
        // the disclosure case's turning chevron is the fourth); Licences and Group settings draw ListRows (Task 4), so
        // About and GroupSettings have none of their own.
        for (file, count) in [("OverviewScreen.swift", 1), ("AccountHubScreen.swift", 0), ("ListRows.swift", 4), ("About.swift", 0),
                              ("GroupSettings.swift", 0),
                              ("GroupPicker.swift", 1), ("RemovalFlow.swift", 1)] {
            XCTAssertEqual(try appSource(file).components(separatedBy: "RowChevron(").count - 1, count, "\(file): RowChevron")
        }
    }

    /// No middle-dot join is left in app UI: only notifications (Push.swift) keep it, and ThemeArt.swift
    /// defines it. Comment lines are skipped.
    func testNoMiddleDotJoinIsLeftInAppUI() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }
        var offenders: [String] = []
        for file in files where file != "Push.swift" && file != "ThemeArt.swift" {
            for (n, line) in try appSource(file).components(separatedBy: "\n").enumerated() {
                let code = line.trimmingCharacters(in: .whitespaces)
                if code.hasPrefix("//") { continue }
                if code.contains(" \u{00B7} ") { offenders.append("\(file):\(n + 1)") }
            }
        }
        XCTAssertEqual(offenders, [])
    }

    /// The pinned counts after the classification (Step 4 reports them; a later site must update these).
    func testSectionLabelsCarryTheMarkAndTheRestDoNot() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") && $0 != "TypeSystem.swift" }
        func count(_ needle: String) throws -> Int {
            try files.reduce(0) { $0 + (try appSource($1).components(separatedBy: needle).count - 1) }
        }
        // D-249: 42, Your spending's total card kicker, its where card and its Expenses label.
        XCTAssertEqual(try count(".sectionLabel("), 43) // D-254: 43, the Sync card's label while not approved. // D-241: the currency list's Suggested label. D-223: Lately's label, inside its card. D-234: App icon's label went (its header row says it).
        // D-221: a block's currency when there are several. D-225 Task 6: 21, Add expense's split hint (A6, A8).
        // D-249: 23, the spending card's "This month" and the table's column heads.
        XCTAssertEqual(try count(".microLabelFont("), 24) // D-254: 24, that card's value (Waiting, Off).
    }

    /// D-217: the Norse ornament's row holds its rune; Android's twin pins `ROW_HEIGHT`. D-226 (art.py section 46): 20
    /// points for the 17 point rune (16 for the 13 point one before).
    func testTheOrnamentsRowHoldsItsRune() throws {
        XCTAssertEqual(TitleOrnament.rowHeight, 20)
        let source = try appSource("ThemeArt.swift")
        XCTAssertEqual(source.components(separatedBy: "static let rowHeight: CGFloat = 20").count - 1, 1, "TitleOrnament.rowHeight")
    }

    /// D-217: a list row's Lucide chevron is drawn in a 20 point box on iOS (Android's test reads this same value
    /// out of the Swift file; this is the iOS side of that pin).
    func testTheRowChevronIs20Points() throws {
        XCTAssertEqual(RowChevron.size, 20)
        let source = try appSource("LucideIcon.swift")
        XCTAssertEqual(source.components(separatedBy: "static let size: CGFloat = 20\n").count - 1, 1, "RowChevron.size")
    }

    // MARK: D-220 Task 4: the navigation bars' ground and the currency list's glyphs

    private func barTraits(_ set: ThemeSet) -> UITraitCollection {
        UITraitCollection { t in
            t.userInterfaceStyle = set.isDark ? .dark : .light
            t[ThemeTrait.self] = set.theme
        }
    }

    private func barHex(_ color: UIColor?, _ set: ThemeSet) -> String? {
        guard let color else { return nil }
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.resolvedColor(with: barTraits(set)).getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X/%.2f", Int(round(r * 255)), Int(round(g * 255)), Int(round(b * 255)), Double(a))
    }

    private func barIsClear(_ color: UIColor?) -> Bool {
        guard let color else { return true }
        var a: CGFloat = 0
        color.getWhite(nil, alpha: &a)
        return a == 0
    }

    /// D-220 (L-D217-3): the navigation bars' ground is the tab bar's (`TabBarGround.of`). Norse and Vardr Labs: an
    /// opaque `surface` with a 1 point `hairline` under it (the tab bar's rule, Android's 1 dp line). Aurora: the
    /// system's own background (its material). The scroll edge is clear in every theme, so a page's top reads as the
    /// art, as before. Android's twin is SkiptiBarTest.the_bar_ground_follows_the_theme.
    @MainActor
    func testTheBarGroundPerTheme() throws {
        for set in ThemeSet.allCases where TabBarGround.of(set.theme) == .solid {
            let a = BarGround.standard(set.theme)
            XCTAssertNil(a.backgroundEffect, "\(set): solid, no material")
            XCTAssertEqual(barHex(a.backgroundColor, set), Tokens.hex(Tokens.palette["surface"]!, set) + "/1.00", "\(set) ground")
            XCTAssertEqual(barHex(a.shadowColor, set), Tokens.hex(Tokens.palette["hairline"]!, set) + "/1.00", "\(set) line")
            let line = try XCTUnwrap(a.shadowImage, "\(set): the line is our 1 point drawing")
            XCTAssertEqual(line.size.height, 1, "\(set): 1 point, as the tab bar's rule")
            XCTAssertEqual(line.renderingMode, .alwaysTemplate, "\(set): the line takes shadowColor")
        }
        XCTAssertEqual(ThemeSet.allCases.filter { TabBarGround.of($0.theme) == .solid }.count, 3, "Norse and both Vardr Labs sets")
        // Aurora is the system's own default background: on iOS 26 and later that has no colour and no material (the
        // system's glass and scroll-edge effect do the work; measured on iOS 27), on iOS 17 to 25 a material.
        let aurora = BarGround.standard(.aurora)
        let system = UINavigationBarAppearance()
        system.configureWithDefaultBackground()
        XCTAssertEqual(aurora.backgroundEffect == nil, system.backgroundEffect == nil, "Aurora keeps the system's background")
        if #unavailable(iOS 26) { XCTAssertNotNil(aurora.backgroundEffect, "Aurora keeps the system's material") }
        for set in [ThemeSet.auroraLight, .auroraDark] {
            XCTAssertEqual(barHex(aurora.backgroundColor, set), barHex(system.backgroundColor, set), "\(set): the system's colour")
            XCTAssertEqual(barHex(aurora.shadowColor, set), barHex(system.shadowColor, set), "\(set): the system's line")
            XCTAssertNotEqual(barHex(aurora.backgroundColor, set), Tokens.hex(Tokens.palette["surface"]!, set) + "/1.00")
        }
        XCTAssertNil(aurora.shadowImage, "Aurora keeps the system's own line")
        // The scroll edge is one appearance for every theme (fix wave: it takes no theme).
        let edge = BarGround.scrollEdge()
        XCTAssertNil(edge.backgroundEffect, "the top of the page reads clear")
        XCTAssertTrue(barIsClear(edge.backgroundColor), "no colour at the scroll edge")
        XCTAssertTrue(barIsClear(edge.shadowColor), "no line at the scroll edge")
        // On iOS 26 and later the system's default background has no material, colour or line either, so the values
        // above cannot tell it from the transparent one (sabotage J10 survived them): the source pins which it is.
        let edgeSource = try XCTUnwrap(try appSource("ThemeArt.swift").components(separatedBy: "static func scrollEdge(title: Attributes").dropFirst().first?
            .components(separatedBy: "\n    }\n").first)
        XCTAssertTrue(edgeSource.contains("appearance.configureWithTransparentBackground()"))
        XCTAssertFalse(edgeSource.contains("configureWithDefaultBackground"))
        XCTAssertFalse(edgeSource.contains("configureWithOpaqueBackground"))
        // The same holds for Aurora's standard ground (fix wave, T4 review Minor 1): the system's default, by source.
        let standard = try slice(try appSource("ThemeArt.swift"), from: "static func standard(_ theme: AppTheme,", to: "\n    }\n")
        let glass = try slice(standard, from: "case .glass:", to: "case .solid:")
        XCTAssertEqual(glass.trimmingCharacters(in: .whitespacesAndNewlines), "appearance.configureWithDefaultBackground()")
        // Each call is a fresh appearance: a caller's copy never leaks into the next theme's.
        XCTAssertFalse(BarGround.standard(.norse) === BarGround.standard(.norse))
    }

    /// D-220: `TitleStyle.apply` sets the ground on the appearance proxy and on every live bar BEFORE iOS 17 to 25's
    /// `BarTint.applyBackButton` copies the bar's appearances (so the copies keep the solid ground), and the back
    /// button's proxy path starts from the theme's ground, not UIKit's bare default.
    func testTitleStyleAppliesTheGround() throws {
        let art = try appSource("ThemeArt.swift")
        let apply = try XCTUnwrap(art.components(separatedBy: "static func apply(_ state: AppearanceState, to window: UIWindow) {").dropFirst().first?
            .components(separatedBy: "static func navigationBars(").first)
        func at(_ s: String) throws -> String.Index { try XCTUnwrap(apply.range(of: s), s).lowerBound }
        XCTAssertLessThan(try at("proxy.standardAppearance = BarGround.standard(state.theme, "), try at("BarTint.applyBackButton(to: proxy"))
        XCTAssertLessThan(try at("proxy.scrollEdgeAppearance = BarGround.scrollEdge(title: inline, "), try at("BarTint.applyBackButton(to: proxy"))
        let live = try XCTUnwrap(apply.components(separatedBy: "for bar in navigationBars(in: window.rootViewController) {").dropFirst().first)
        func liveAt(_ s: String) throws -> String.Index { try XCTUnwrap(live.range(of: s), s).lowerBound }
        XCTAssertLessThan(try liveAt("bar.standardAppearance = BarGround.standard(state.theme, "), try liveAt("BarTint.applyBackButton(to: bar"))
        XCTAssertLessThan(try liveAt("bar.scrollEdgeAppearance = BarGround.scrollEdge(title: inline, "), try liveAt("BarTint.applyBackButton(to: bar"))
        let back = try XCTUnwrap(art.components(separatedBy: "static func applyBackButton(").dropFirst().first?
            .components(separatedBy: "\n    }\n").first)
        XCTAssertTrue(back.contains("let standard = proxy ? BarGround.standard(theme) : bar.standardAppearance"))
        XCTAssertTrue(back.contains("(proxy ? BarGround.scrollEdge() : bar.scrollEdgeAppearance)"))
        XCTAssertFalse(back.contains("UINavigationBarAppearance()"), "the proxy path starts from the ground")
    }

    /// D-220: a theme switch with a screen open: a live bar goes solid in Norse and back to the system's material in
    /// Aurora, every appearance (compact ones included) following, none a stale copy of the last theme's.
    @MainActor
    func testALiveBarTakesTheGroundAndLetsItGo() throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        let nav = UINavigationController(rootViewController: UIViewController())
        window.rootViewController = nav // never made key: the test host's own window stays in front
        defer { TitleStyle.apply(AppearanceState.shared, to: window) } // the proxy back to the host's theme
        let bar = nav.navigationBar
        let state = AppearanceState()
        state.theme = .norse
        TitleStyle.apply(state, to: window)
        for (name, a) in [("standard", bar.standardAppearance), ("compact", bar.compactAppearance ?? bar.standardAppearance)] {
            XCTAssertNil(a.backgroundEffect, "Norse \(name)")
            XCTAssertEqual(barHex(a.backgroundColor, .norse), Tokens.hex(Tokens.palette["surface"]!, .norse) + "/1.00", "Norse \(name)")
        }
        XCTAssertTrue(barIsClear((bar.scrollEdgeAppearance ?? bar.standardAppearance).backgroundColor), "Norse scroll edge")
        state.theme = .aurora
        TitleStyle.apply(state, to: window)
        let system = UINavigationBarAppearance()
        system.configureWithDefaultBackground()
        for (name, a) in [("standard", bar.standardAppearance), ("compact", bar.compactAppearance ?? bar.standardAppearance)] {
            XCTAssertEqual(a.backgroundEffect == nil, system.backgroundEffect == nil, "Aurora \(name): the system's background again")
            XCTAssertNil(a.shadowImage, "Aurora \(name): no solid line left")
            XCTAssertNotEqual(barHex(a.backgroundColor, .auroraDark), Tokens.hex(Tokens.palette["surface"]!, .auroraDark) + "/1.00", "Aurora \(name)")
        }
    }

    /// D-220 (L-D217-6): the currency list's two glyphs are Lucide's `search` and `check` (NOTICE), as Android's,
    /// at Android's 20; the selected row still says so to VoiceOver.
    func testTheCurrencyListUsesLucide() throws {
        let source = try appSource("CurrencyChoice.swift")
        XCTAssertFalse(source.contains("systemName: \"magnifyingglass\""))
        XCTAssertFalse(source.contains("systemName: \"checkmark\""))
        XCTAssertFalse(source.contains("Image(systemName:"), "no SF Symbol left in the list")
        XCTAssertEqual(source.components(separatedBy: "LucideIcon(glyph: .search, size: 20)").count - 1, 1)
        XCTAssertEqual(source.components(separatedBy: "LucideIcon(glyph: .check, size: 20)").count - 1, 1)
        let check = try XCTUnwrap(source.components(separatedBy: "LucideIcon(glyph: .check").dropFirst().first?.prefix(200))
        XCTAssertTrue(check.contains(".accessibilityLabel(\"Selected\")"), "the check keeps its label")
        XCTAssertTrue(check.contains(".foregroundStyle(Tokens.accent)"))
    }

    /// D-220 fix wave (I4): the currency list is the theme's cards, as Android's: the search in its own card, "Common"
    /// and "All currencies" as section labels over a card of rows each (hairline rules between rows), never the
    /// system's rounded inset-grouped cells. "Common" shows only while the search is empty, on both shells.
    func testTheCurrencyListIsTheThemesCards() throws {
        let screen = try slice(try appSource("CurrencyChoice.swift"), from: "struct CurrencyListScreen: View {", to: "\n}\n")
        XCTAssertFalse(screen.contains("List {"), "no system list")
        XCTAssertFalse(screen.contains("Section {"), "no system section")
        XCTAssertTrue(screen.contains("ScrollView {"))
        XCTAssertEqual(count(screen, ".glassCard()"), 3, "the search, a card of rows, the no-match line")
        XCTAssertEqual(count(screen, "card(AddExpenseScreen.promotedCurrencies)"), 1, "Common")
        XCTAssertEqual(count(screen, "card(shown)"), 1, "All currencies")
        XCTAssertEqual(count(screen, "if query.isEmpty {"), 1, "Common hides while searching")
        XCTAssertEqual(count(screen, "Text(verbatim: CurrencyCopy.common).sectionLabel()"), 1)
        XCTAssertEqual(count(screen, "Text(verbatim: CurrencyCopy.everything).sectionLabel()"), 1)
        XCTAssertTrue(screen.contains("Tokens.hairline"), "the rule between rows")
        XCTAssertFalse(screen.contains("auroraFormBackground"), "a Form's ground on a page that is no Form")
        XCTAssertTrue(screen.contains(".auroraBackground()"))
    }

    /// D-220 fix wave (I1): each "People in X" link on a Groups card (the photo card's and the plain card's) is at
    /// least 44 pt tall with its whole frame the tap; a text-only label is hit only on its line, about 17 pt.
    func testThePeopleLinksAreFortyFourPointsTall() throws {
        let links = try appSource("GroupsScreen.swift")
            .components(separatedBy: "PeopleScreen(store: store, groupID: named.group.id) { reload(); OverviewActions.changed() }").dropFirst()
        XCTAssertEqual(links.count, 2, "the photo card's link and the plain card's")
        for link in links {
            let label = try slice(link, from: "} label: {", to: "\n                }\n")
            XCTAssertTrue(label.contains("GroupsCopy.people(named.group.name)"), label)
            XCTAssertTrue(label.contains(".frame(minHeight: 44, alignment: .leading)"), label)
            XCTAssertTrue(label.contains(".contentShape(Rectangle())"), label)
        }
    }

    /// D-220 fix wave (I3): where the theme decides a card's shape, no screen draws its own rounded one: no
    /// `cornerRadius:` from the card radius outside the decisions (ThemeArt.swift) and the glass card (DesignTokens.swift).
    /// The two hero cards, the removal choice's ring and Profile's card hit shape take `ThemeArt.cardShape`, and Norse's
    /// hero edge follows the notch. No exception is left (D-222 drew the theme chooser's swatches as each theme's art).
    func testNoScreenDrawsItsOwnCardShape() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }
        XCTAssertGreaterThan(files.count, 50, "the app sources were found")
        let own = try NSRegularExpression(pattern: #"cornerRadius:[^\n]*cardRadius"#)
        let allowed: Set<String> = [] // D-222 (L60): the theme chooser's miniature card went with the swatches
        var leaks: [String] = []
        for file in files where !["ThemeArt.swift", "DesignTokens.swift"].contains(file) {
            for line in try appSource(file).components(separatedBy: "\n")
            where own.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) != nil {
                let entry = "\(file): \(line.trimmingCharacters(in: .whitespaces))"
                if !allowed.contains(entry) { leaks.append(entry) }
            }
        }
        XCTAssertEqual(leaks, [])
        let sites: [(file: String, call: String)] = [
            ("OverviewScreen.swift", ".background { HeroArt().clipShape(ThemeArt.cardShape(AppearanceState.shared.theme)) }"),
            ("SignInScreen.swift", ".background { HeroArt().clipShape(ThemeArt.cardShape(AppearanceState.shared.theme)) }"),
            ("RemovalFlow.swift", "let ring = ThemeArt.cardShape(AppearanceState.shared.theme)"),
            ("Profile.swift", ".contentShape(ThemeArt.cardShape(AppearanceState.shared.theme))"),
        ]
        for site in sites {
            XCTAssertEqual(count(try appSource(site.file), site.call), 1, "\(site.file): \(site.call)")
        }
        // The ring is 2 pt inside the card's shape (a stroke twice the width, clipped: an `AnyShape` cannot inset).
        XCTAssertEqual(count(try appSource("RemovalFlow.swift"), "ring.stroke(picked == value ? Tokens.accent : Color.clear, lineWidth: 4).clipShape(ring)"), 1)
        // Norse's hero edge: 1 pt inside the notch on its straight sides, as a Norse card's edge (D-226 fix wave, I1 /
        // decision 29; it was a rectangle cut off at the corners, then the whole notched outline).
        let norse = try slice(try slice(try appSource("ThemeMotion.swift"), from: "struct HeroArt: View", to: "\n}\n"), from: "case .norse:", to: "case .vardr:")
        XCTAssertEqual(count(norse, #"NotchEdges(chamfer: ThemeArt.m("chamfer")).stroke(ThemeArt.norseEdge, lineWidth: 1).clipShape(ThemeArt.cardShape(.norse))"#), 1)
        XCTAssertFalse(norse.contains("Rectangle().strokeBorder(ThemeArt.norseEdge"), "the square edge")
    }

    /// D-221 (Q2 a, K05, K06): Home's account badge is the one accent avatar; every other avatar is soft. No hairline
    /// and no raised surface: the fill is the token pair, the edge is Norse's alone, the initials the display face at
    /// 700 in every theme. The two old decisions are gone with their reader.
    func testAvatarsTakeTheirFill() throws {
        let profile = try appSource("Profile.swift")
        let avatar = try slice(profile, from: "struct AvatarView: View {", to: "\n}\n")
        XCTAssertTrue(avatar.contains("var fill: AvatarFill = .soft"), "soft by default")
        XCTAssertTrue(avatar.contains("Circle().fill(fill.ground)"), "the fill's ground")
        XCTAssertEqual(count(avatar, ".foregroundStyle(fill.ink)"), 2, "the initials and the glyph in the fill's ink")
        XCTAssertTrue(avatar.contains(".font(TypeRole.display(size * TypeScale.avatarInitial, weight: 700))"), "display face at 700")
        XCTAssertTrue(avatar.contains("if let edge = AvatarStyle.edgeHex(AppearanceState.shared.theme) {"), "Norse's edge only")
        XCTAssertTrue(avatar.contains("Circle().stroke(Color(UIColor(hex: edge)), lineWidth: 1)"))
        XCTAssertFalse(avatar.contains("Tokens.hairline"), "the hairline goes")
        XCTAssertFalse(avatar.contains("Tokens.surfaceRaised"), "the raised surface goes")
        XCTAssertFalse(avatar.contains("TypeRole.body("), "no body-face initials")
        let badge = try slice(profile, from: "struct ProfileBadge: View {", to: "\n}\n")
        XCTAssertTrue(badge.contains("AvatarView(avatar: avatar, name: name, size: size, fill: .accent)"), "the badge is accent")
        // Every other avatar takes the default: the accent fill is the badge's alone.
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        var accents = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path).filter({ $0.hasSuffix(".swift") }) {
            accents += count(try appSource(file), "fill: .accent)")
        }
        XCTAssertEqual(accents, 1, "one accent avatar")
        let art = try appSource("ThemeArt.swift")
        XCTAssertFalse(art.contains("func ringHex("))
        XCTAssertFalse(art.contains("func initialsUseDisplayFace("))
    }

    /// D-221 (Design 10): the compact primary (the next cards' "Do it") keeps the full button's looks, shape and
    /// disabled alpha, and only drops the full width, the label size and the padding.
    func testCompactPrimaryKeepsTheLook() throws {
        let tokens = try appSource("DesignTokens.swift")
        let style = try slice(tokens, from: "struct PrimaryButtonStyle: ButtonStyle {", to: "\n}\n")
        XCTAssertTrue(style.contains("var compact = false"))
        XCTAssertTrue(style.contains("PrimaryButtonBody(configuration: configuration, tint: tint, compact: compact)"))
        let body = try slice(tokens, from: "private struct PrimaryButtonBody: View {", to: "\n}\n")
        XCTAssertTrue(body.contains("private var maxWidth: CGFloat? { compact ? nil : .infinity }"), "no full width when compact")
        XCTAssertEqual(count(body, ".frame(maxWidth: maxWidth)"), 2, "both looks")
        XCTAssertFalse(body.contains(".frame(maxWidth: .infinity)"), "the compact branch never fills the width")
        XCTAssertTrue(body.contains("private var labelSize: CGFloat { compact ? 15 : 17 }"))
        XCTAssertEqual(count(body, ".font(TypeRole.label(labelSize))"), 2)
        XCTAssertTrue(body.contains(".tracking(ButtonLook.tracking(labelSize))"))
        XCTAssertEqual(count(body, ".padding(.vertical, compact ? 10 : 15)"), 2)
        XCTAssertEqual(count(body, ".padding(.horizontal, compact ? 16 : 18)"), 2)
        XCTAssertTrue(body.contains("ButtonLook.of("), "the same filled and outlined looks")
        XCTAssertEqual(count(body, "ButtonLook.disabledAlpha"), 2, "the same disabled alpha")
        XCTAssertTrue(body.contains(".clipShape(ThemeArt.controlShape(AppearanceState.shared.theme))"), "the same shape")
    }
}

/// D-225 follow-up (R1): the switch's state from outside the view, flipped with no animation in its transaction, as a
/// list row's tap flips its binding.
private final class SwitchProbeState: ObservableObject {
    @Published var on = false
}

private struct SwitchProbe: View {
    @ObservedObject var state: SwitchProbeState
    let reduceMotion: Bool

    var body: some View {
        ThemeSwitch(isOn: state.on)
            .environment(\.motionReduced, reduceMotion)
            .padding(8)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .ignoresSafeArea()
    }
}
