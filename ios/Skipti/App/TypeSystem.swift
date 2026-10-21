import SwiftUI
import UIKit

/// §11.3's four roles, wired to the BUNDLED TTFs and riding Dynamic Type
/// through UIFontMetrics, so §11.7's 200% requirement is structural rather
/// than aspirational.
///
/// Tabular figures are set EXPLICITLY (ADR-0023 §6: neither face declares
/// tabular-nums anywhere by default; Space Grotesk carries the tnum feature,
/// Plex Mono is mono by construction but gets the descriptor anyway, so the
/// rule never depends on which face a string lands in).
enum TypeRole {
    static let grotesk = "SpaceGrotesk-Light" // variable default; wght axis driven below
    /// D-148: IBM's own files (release @ibm/plex-mono 2.5.0) name their
    /// PostScript faces "IBMPlexMono" and "IBMPlexMono-Medm"; the Google
    /// Fonts subset they replace said "-Regular" and "-Medium". A name that
    /// does not resolve falls back to the system font without a word, so
    /// every lookup goes through these two constants and a test resolves them.
    static let plexRegular = "IBMPlexMono"
    static let plexMedium = "IBMPlexMono-Medm"
    /// D-212: Norse's face, the google/fonts files (static 600 and 700).
    static let cormorantSemiBold = "CormorantSC-SemiBold"
    static let cormorantBold = "CormorantSC-Bold"

    /// D-215 (the founder, 2026-10-15: both phones identical, type included): IBM's own Plex Sans files
    /// (release @ibm/plex-sans 1.1.0, unmodified, Reserved Font Name "Plex") replace the system face.
    static let sansRegular = "IBMPlexSans"
    static let sansMedium = "IBMPlexSans-Medm"
    static let sansSemiBold = "IBMPlexSans-SmBld"
    static let sansBold = "IBMPlexSans-Bold"

    /// The static Plex Sans cut for a requested weight, in the system face's old buckets.
    static func sansName(weight: CGFloat) -> String {
        weight >= 650 ? sansBold : weight >= 550 ? sansSemiBold : weight >= 450 ? sansMedium : sansRegular
    }

    /// D-215: Vardr Runes Carved Regular, Vardr Labs' own font (release v1.000 of vardrlabs/fonts), Norse's runes. As
    /// above, a name that does not resolve falls back to the system font without a word: this constant is the only
    /// place the name is written, and a test opens the bundled file and resolves it.
    static let runes = "VardrRunesCarved-Regular"

    /// The rune descriptor every text face carries as its FIRST fallback (a Core Text cascade list): none of the
    /// bundled text faces has a Runic glyph, so a rune inside ordinary text (a meta line's separator, a section
    /// label's mark) would otherwise be drawn by the system's Apple Symbols. Latin text never reaches it.
    static let runeCascade = UIFontDescriptor(fontAttributes: [.name: runes])

    /// A rune at a point size: FIXED (it does not move with Dynamic Type, as the art's sizes never did) unless a text
    /// style is given, which scales it as that style's text would. The unscaled-or-scaled font `rune` draws.
    static func runeUIFont(_ size: CGFloat, relativeTo style: UIFont.TextStyle?, category: UIContentSizeCategory) -> UIFont {
        let base = UIFont(name: runes, size: size) ?? .systemFont(ofSize: size)
        guard let style else { return base }
        return UIFontMetrics(forTextStyle: style).scaledFont(for: base, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    /// The rune role: every rune SKIPTI draws on its own (tab icons, the ornament, the rising runes, the hero
    /// watermark) and the section mark and separators inside text.
    static func rune(_ size: CGFloat, relativeTo style: UIFont.TextStyle? = nil) -> Font {
        Font(runeUIFont(size, relativeTo: style, category: AppearanceState.shared.contentSizeCategory))
    }

    enum Face { case grotesk, sans, cormorant, plex }
    enum Role: CaseIterable { case display, body, numeric, label, microLabel }

    /// The plan's table (ADR-0041, read against the Look Book).
    static func face(_ role: Role, theme: AppTheme) -> Face {
        switch (role, theme) {
        case (.display, .norse): return .cormorant
        case (.display, _): return .grotesk
        case (.body, .vardr): return .grotesk
        case (.body, _): return .sans
        case (.numeric, .vardr): return .plex
        case (.numeric, _): return .grotesk
        case (.label, .vardr): return .plex
        case (.label, _): return .sans
        case (.microLabel, .aurora): return .sans
        case (.microLabel, .norse): return .cormorant
        case (.microLabel, .vardr): return .plex
        }
    }

    /// The wght axis's identifier: Core Text keys variation axes by their
    /// four-character tag AS A NUMBER. D-174: the string "wght" was ignored
    /// without a word, so every Space Grotesk weight drew the font's default
    /// instance (Light 300) since the type roles were written.
    static let weightAxis = NSNumber(value: 0x7767_6874) // 'wght'

    /// The font a role draws with, before Dynamic Type scaling (visible to
    /// tests, which read its variation back).
    static func uiFont(_ name: String, size: CGFloat, weight: CGFloat?, tabular: Bool) -> UIFont {
        UIFont(descriptor: descriptor(name, size: size, weight: weight, tabular: tabular), size: size)
    }

    /// The descriptor `uiFont` draws from (the variation and the explicit tnum feature are readable here, though
    /// a UIFont made from it does not report them back).
    static func descriptor(_ name: String, size: CGFloat, weight: CGFloat?, tabular: Bool) -> UIFontDescriptor {
        var attributes: [UIFontDescriptor.AttributeName: Any] = [.cascadeList: [runeCascade]]
        if let weight {
            // Variable-font weight via the wght axis (Space Grotesk 300-700).
            let variationKey = kCTFontVariationAttribute as String
            attributes[.init(rawValue: variationKey)] = [weightAxis: weight]
        }
        if tabular {
            // kNumberSpacingType / kMonospacedNumbersSelector == tnum.
            let feature: [String: Int] = [
                kCTFontFeatureTypeIdentifierKey as String: kNumberSpacingType,
                kCTFontFeatureSelectorIdentifierKey as String: kMonospacedNumbersSelector,
            ]
            attributes[.featureSettings] = [feature]
        }
        let base = UIFont(name: name, size: size) ?? .systemFont(ofSize: size)
        return base.fontDescriptor.addingAttributes(attributes)
    }

    /// The unscaled font for a face at a requested weight.
    static func base(_ face: Face, size: CGFloat, weight: CGFloat, tabular: Bool) -> UIFont {
        switch face {
        case .grotesk:
            return uiFont(grotesk, size: size, weight: weight, tabular: tabular)
        case .sans:
            return uiFont(sansName(weight: weight), size: size, weight: nil, tabular: tabular)
        case .cormorant:
            return uiFont(weight >= 650 ? cormorantBold : cormorantSemiBold, size: size, weight: nil, tabular: tabular)
        case .plex:
            return uiFont(weight >= 500 ? plexMedium : plexRegular, size: size, weight: nil, tabular: tabular)
        }
    }

    /// Scaled to the category SKIPTI draws at (the phone's, moved by the
    /// text-size step), not the window's trait alone: `AppearanceState` is
    /// observable, so a body that calls a role re-evaluates when it changes.
    static func scaledSize(_ size: CGFloat, relativeTo style: UIFont.TextStyle, category: UIContentSizeCategory) -> CGFloat {
        UIFontMetrics(forTextStyle: style).scaledValue(for: size, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    private static func scaled(_ font: UIFont, _ style: UIFont.TextStyle) -> Font {
        let traits = UITraitCollection(preferredContentSizeCategory: AppearanceState.shared.contentSizeCategory)
        return Font(UIFontMetrics(forTextStyle: style).scaledFont(for: font, compatibleWith: traits))
    }

    private static func font(_ role: Role, size: CGFloat, weight: CGFloat, tabular: Bool, relativeTo style: UIFont.TextStyle) -> Font {
        let unscaled = base(face(role, theme: AppearanceState.shared.theme), size: size, weight: weight, tabular: tabular)
        return scaled(unscaled, style)
    }

    /// Display: screen titles, balances, the settle-up number, in the theme's face.
    static func display(_ size: CGFloat = 40, weight: CGFloat = 500) -> Font {
        font(.display, size: size, weight: weight, tabular: true, relativeTo: .largeTitle)
    }

    /// D-224 fix wave (F9): the scaled font `display` draws, for a title that measures its own words (`GreedyText`).
    static func displayUIFont(_ size: CGFloat = 40, weight: CGFloat = 500, category: UIContentSizeCategory,
                              theme: AppTheme) -> UIFont {
        let unscaled = base(face(.display, theme: theme), size: size, weight: weight, tabular: true)
        return UIFontMetrics(forTextStyle: .largeTitle).scaledFont(
            for: unscaled, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    /// Body: descriptions, notes, in the theme's face. 16px/1.5 base. D-217: the weight is 400 unless a place asks
    /// for another (You's handle, 500).
    static func body(_ size: CGFloat = 16, weight: CGFloat = 400) -> Font {
        font(.body, size: size, weight: weight, tabular: false, relativeTo: .body)
    }

    /// D-217: a person's or group's name wherever it is drawn as a name (rows, headers, the You card): the theme's
    /// BODY face at 600, scaled like `body`. Android's twin is `Type.name`.
    static func name(_ size: CGFloat = 16) -> Font {
        Font(nameUIFont(size, category: AppearanceState.shared.contentSizeCategory, theme: AppearanceState.shared.theme))
    }

    /// D-234: the scaled font `body` draws, for text that measures its own words (`GreedyText`) and for tests.
    static func bodyUIFont(_ size: CGFloat = 16, weight: CGFloat = 400, category: UIContentSizeCategory, theme: AppTheme) -> UIFont {
        let unscaled = base(face(.body, theme: theme), size: size, weight: weight, tabular: false)
        return UIFontMetrics(forTextStyle: .body).scaledFont(for: unscaled, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    /// The scaled font `name` draws (visible to tests, which read its face and point size back).
    static func nameUIFont(_ size: CGFloat, category: UIContentSizeCategory, theme: AppTheme) -> UIFont {
        let unscaled = base(face(.body, theme: theme), size: size, weight: 600, tabular: false)
        return UIFontMetrics(forTextStyle: .body).scaledFont(for: unscaled, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    /// Body face at a FIXED size: it does not scale with Dynamic Type, for the few places that show a range
    /// (the text-size slider's two A's) and must not move with the setting. D-222 (L65): at a weight, 400 unless asked
    /// (the large A is 700). Android's twin is `dp.toSp()` with the weight's `FontWeight`.
    static func bodyFixed(_ size: CGFloat = 16, weight: CGFloat = 400) -> Font {
        Font(bodyFixedUIFont(size, weight: weight, theme: AppearanceState.shared.theme))
    }

    /// The unscaled font `bodyFixed` draws (visible to tests).
    static func bodyFixedUIFont(_ size: CGFloat, weight: CGFloat = 400, theme: AppTheme) -> UIFont {
        base(face(.body, theme: theme), size: size, weight: weight, tabular: false)
    }

    /// Numeric: amounts, counts, dates, with tabular figures so columns
    /// align. D-174 (ADR-0038): Space Grotesk, a requested 400 drawing at 450
    /// and 500 at 600; D-212: Plex Mono in Vardr Labs (Regular below 500,
    /// Medium from it).
    static func numeric(_ size: CGFloat = 15, weight: CGFloat = 400, relativeTo style: UIFont.TextStyle = .body) -> Font {
        Font(numericUIFont(size, weight: weight, relativeTo: style,
                           category: AppearanceState.shared.contentSizeCategory, theme: AppearanceState.shared.theme))
    }

    /// The scaled font `numeric` draws (visible to tests, which read its point size back).
    static func numericUIFont(_ size: CGFloat, weight: CGFloat, relativeTo style: UIFont.TextStyle,
                              category: UIContentSizeCategory, theme: AppTheme) -> UIFont {
        let face = self.face(.numeric, theme: theme)
        let drawn: CGFloat = face == .grotesk ? (weight >= 500 ? 600 : 450) : weight
        let unscaled = base(face, size: size, weight: drawn, tabular: true)
        return UIFontMetrics(forTextStyle: style).scaledFont(for: unscaled, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
    }

    /// Mono: IBM Plex Mono in every theme, kept only where letter shapes must
    /// be unambiguous to copy by hand: the Recovery Kit's words and the
    /// six-word device phrase (ADR-0038).
    static func mono(_ size: CGFloat = 15, weight: CGFloat = 400) -> Font {
        let name = weight >= 500 ? plexMedium : plexRegular
        return scaled(uiFont(name, size: size, weight: nil, tabular: true), .body)
    }

    /// Label: a button's words, and anything that should read as a heading
    /// at body size (D-174), in the theme's face.
    static func label(_ size: CGFloat = 17) -> Font {
        font(.label, size: size, weight: 600, tabular: true, relativeTo: .body)
    }

    /// Section label in the theme's face. D-174 (ADR-0038): sentence case at
    /// 13 points and a weight heavier; D-212: Vardr Labs sets it uppercase
    /// and tracked, through `microLabelFont`.
    static func microLabel(_ size: CGFloat = 13) -> Font {
        font(.microLabel, size: size, weight: 600, tabular: false, relativeTo: .footnote)
    }

    /// D-212 (fix wave 3, F5): a tab's label is drawn in the BODY face in every theme (the Look Book's tabs use
    /// `--p-body`), never uppercase or tracked. Android's twin is `Type.tabFace`.
    static func tabFace(theme: AppTheme = AppearanceState.shared.theme) -> Face {
        face(.body, theme: theme)
    }

    /// A tab's label: the body face at weight 600, scaled like the micro label it replaces (relative to
    /// `.footnote`, 11 points).
    static func tabLabel(_ size: CGFloat = 11) -> Font {
        scaled(base(tabFace(), size: size, weight: 600, tabular: false), .footnote)
    }

    /// D-212: Vardr Labs' kickers are tracked .22 em (the Look Book) of the
    /// size they draw at, so the tracking scales with the text size; the
    /// other themes keep D-174's zero.
    static func microTracking(_ size: CGFloat = 13, theme: AppTheme = AppearanceState.shared.theme) -> CGFloat {
        guard theme == .vardr else { return 0 }
        return scaledSize(size, relativeTo: .footnote, category: AppearanceState.shared.contentSizeCategory) * 0.22
    }

    /// D-212: Vardr Labs' kickers are uppercase; the others sentence case.
    static func microCase(theme: AppTheme = AppearanceState.shared.theme) -> Text.Case? {
        theme == .vardr ? .uppercase : nil
    }
}

extension View {
    /// D-212: a section label in the theme's face, case and tracking.
    func microLabelFont(_ size: CGFloat = 13) -> some View {
        font(TypeRole.microLabel(size))
            .tracking(TypeRole.microTracking(size))
            .textCase(TypeRole.microCase())
    }

    /// D-213: a SECTION label (it names what follows): the micro face, with ᛟ before it in Norse. Status words,
    /// hints and row flags keep `microLabelFont` (the plan's rule).
    func sectionLabel(_ size: CGFloat = 13) -> some View {
        microLabelFont(size).modifier(SectionLabelMark(size: size))
    }
}

/// D-217 (the founder, 2026-10-16: both phones draw the same sizes, and where they differed iOS's value is the
/// scale): every size a screen takes from the scale, in points here and sp on Android. One table per shell; Android's
/// `TypeScale` (Tokens.kt) is read against this one name by name and value by value (TypeThemeTest). `avatarInitial`
/// is a fraction of the avatar's own size, not a point size. Add a constant here and its twin there in one change.
enum TypeScale {
    static let largeTitle: CGFloat = 34
    static let inlineTitle: CGFloat = 17
    static let dateLine: CGFloat = 14
    static let heroAmount: CGFloat = 44
    static let heroLine: CGFloat = 15
    static let settledLine: CGFloat = 13
    static let groupCount: CGFloat = 13
    static let detail: CGFloat = 14
    static let statValue: CGFloat = 16
    static let settleIntro: CGFloat = 15
    static let shareInput: CGFloat = 15
    static let avatarInitial: CGFloat = 0.38
    /// D-221: You's name, the group page's name and the celebration's title (art.py 22).
    static let displayName: CGFloat = 24
    /// D-221: the Settled card's percent.
    static let settledPercent: CGFloat = 28
    /// D-221: the milestone seal's number.
    static let sealNumber: CGFloat = 15
    /// D-221: the progress pill's words (art.py 21).
    static let pill: CGFloat = 13
    /// D-222: the mode control's words (art.py 26).
    static let segment: CGFloat = 14
    /// D-222: Appearance's live sample line, scaled with the text size (art.py 27).
    static let sample: CGFloat = 19
    /// D-222: the text-size slider's two A's, fixed: they show the range (art.py 27; the small at 400, the large at 700).
    static let rangeSmall: CGFloat = 14
    static let rangeLarge: CGFloat = 23
    /// D-223: the ledger card's name (art.py 30), Lately's bold line (art.py 32), the empty state's title (art.py 34).
    static let ledgerName: CGFloat = 16
    static let feedLine: CGFloat = 15
    static let emptyTitle: CGFloat = 20
    /// D-224: the done toast's words (art.py 38: the Look Book's 600 12 x 1.28).
    static let toast: CGFloat = 15
    /// D-225 (art.py sections 41 to 44): About's text and the Licences intro, a licence's own text, the lock screen's
    /// title, About's "SKIPTI".
    static let prose: CGFloat = 15
    static let footnote: CGFloat = 13
    static let lockTitle: CGFloat = 28
    static let wordmark: CGFloat = 32

    /// Every constant by its name, in the order above (the test walks it against the agreed table).
    static let all: [(String, CGFloat)] = [
        ("largeTitle", largeTitle), ("inlineTitle", inlineTitle), ("dateLine", dateLine), ("heroAmount", heroAmount),
        ("heroLine", heroLine), ("settledLine", settledLine), ("groupCount", groupCount), ("detail", detail),
        ("statValue", statValue), ("settleIntro", settleIntro), ("shareInput", shareInput), ("avatarInitial", avatarInitial),
        ("displayName", displayName), ("settledPercent", settledPercent), ("sealNumber", sealNumber), ("pill", pill),
        ("segment", segment), ("sample", sample), ("rangeSmall", rangeSmall), ("rangeLarge", rangeLarge),
        ("ledgerName", ledgerName), ("feedLine", feedLine), ("emptyTitle", emptyTitle), ("toast", toast),
        ("prose", prose), ("footnote", footnote), ("lockTitle", lockTitle), ("wordmark", wordmark),
    ]
}

/// D-224 fix wave (F9): a line break the way Android's text makes it. iOS's text system moves a word down so that a last
/// line never holds one word alone; Android's fills each line greedily. With the same room the two phones broke the
/// erased title in different places ("This phone's ledger / is erased" against "This phone's ledger is / erased").
/// `lines` breaks greedily: as many words on a line as fit in `width`, a word wider than it on a line of its own.
enum GreedyWrap {
    static func lines(_ text: String, width: CGFloat, measure: (String) -> CGFloat) -> [String] {
        var lines: [String] = []
        var line = ""
        for word in text.split(separator: " ").map(String.init) {
            let longer = line.isEmpty ? word : line + " " + word
            if line.isEmpty || measure(longer) <= width {
                line = longer
            } else {
                lines.append(line)
                line = word
            }
        }
        if !line.isEmpty { lines.append(line) }
        return lines
    }

    /// The width `text` draws at in `font`, unrounded (D-224 follow-up, R1: rounding up to a whole point broke Norse's
    /// erased title, 361.536 wide, out of its 362 of room).
    static func width(_ text: String, font: UIFont) -> CGFloat {
        (text as NSString).size(withAttributes: [.font: font]).width
    }
}

/// D-224 fix wave (F9): a title broken greedily (`GreedyWrap`) across the width it is given, in `font` (measured and drawn
/// in the same font), so it breaks where Android's twin does. Each line it makes is its own paragraph, so iOS has no
/// last word to move. Until its width is known it draws the plain text.
struct GreedyText: View {
    let text: String
    let font: UIFont
    /// D-234 (L5): where the lines sit across the width: centred (the erased title), or leading (a page's paragraph).
    let alignment: Alignment
    @State private var width: CGFloat = 0

    init(_ text: String, font: UIFont, alignment: Alignment = .center) {
        self.text = text
        self.font = font
        self.alignment = alignment
    }

    /// The lines `text` breaks into in `font` across `room`: its exact width against the whole room, as Android's
    /// line breaker compares them (D-224 follow-up, R1: a point taken off the room broke Norse's title where Android's
    /// fits it on one line).
    static func lines(_ text: String, font: UIFont, room: CGFloat) -> [String] {
        GreedyWrap.lines(text, width: room, measure: { GreedyWrap.width($0, font: font) })
    }

    var body: some View {
        Text(verbatim: width > 1 ? GreedyText.lines(text, font: font, room: width).joined(separator: "\n") : text)
            .font(Font(font))
            .frame(maxWidth: .infinity, alignment: alignment)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .accessibilityLabel(Text(verbatim: text))
    }
}
