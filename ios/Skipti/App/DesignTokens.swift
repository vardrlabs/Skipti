import SwiftUI

/// D-212 (ADR-0041, the founder, 2026-10-14): five token sets, one per theme
/// and effective mode, from the approved Look Book v2.1. Columns, in order:
/// Aurora light, Aurora dark, Norse (always dark), Vardr Labs light, Vardr
/// Labs dark. A column resolves from the trait collection (the style and
/// `ThemeTrait`). Every floor is measured by AppearanceTests; values and the
/// nudges that met the floors come from .drafts/d212/palette.py. Money is
/// never colour alone: every amount carries its sign and its words.
enum Tokens {
    typealias Column = (auroraLight: String, auroraDark: String, norse: String, vardrLight: String, vardrDark: String)

    static func hex(_ column: Column, _ set: ThemeSet) -> String {
        switch set {
        case .auroraLight: return column.auroraLight
        case .auroraDark: return column.auroraDark
        case .norse: return column.norse
        case .vardrLight: return column.vardrLight
        case .vardrDark: return column.vardrDark
        }
    }

    /// The UIColor behind a column, resolved per trait (visible to tests).
    static func uiColor(_ column: Column) -> UIColor {
        UIColor { trait in
            let set = ThemeSet.resolve(trait[ThemeTrait.self], dark: trait.userInterfaceStyle == .dark)
            return UIColor(hex: hex(column, set))
        }
    }

    static func uiColor(_ name: String) -> UIColor { uiColor(palette[name]!) }

    static func dynamic(_ column: Column) -> Color { Color(uiColor(column)) }

    static let palette: [String: Column] = [
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

    private static func token(_ name: String) -> Color { dynamic(palette[name]!) }

    // Structural
    static let bg = token("bg")
    static let surface = token("surface")
    static let surfaceRaised = token("surfaceRaised")
    static let hairline = token("hairline")
    static let text = token("text")
    static let textDim = token("textDim")
    static let textFaint = token("textFaint")
    /// D-214 (the founder, 2026-10-15): faint and accent text sitting directly on the page ground, over the moving
    /// art; in Aurora light the label colour, in Aurora dark the label colour and the accent; in Norse the label colour
    /// and the accent (D-226: its page glows, .drafts/d226/art.py section 48); in Vardr Labs textFaint and accent.
    static let groundFaint = token("groundFaint")
    static let groundAccent = token("groundAccent")

    // Money: the only semantic colours. `owed` is what you owe, `owing` what
    // you are owed; the sign and the words are ALWAYS printed beside them.
    static let owed = token("owed")
    static let owing = token("owing")
    static let neutral = token("neutral")

    // State: WATCH, for an unverified member (SPEC §3.2, D-161).
    static let watch = token("watch")

    // Accent: controls, links, tab text; 4.5:1 on every surface.
    static let accent = token("accent")
    static let onAccent = token("onAccent")
    static let accentSoft = token("accentSoft")
    static let owingSoft = token("owingSoft")
    static let owedSoft = token("owedSoft")

    // E4's ring and settled meter (drawn from E4; measured now).
    static let ring = token("ring")
    static let ringTrack = token("ringTrack")
    static let meterStart = token("meterStart")
    static let meterEnd = token("meterEnd")

    // The hero card's ground and its text (replaces the hard-coded white).
    static let heroStart = token("heroStart")
    static let heroMid = token("heroMid")
    static let heroEnd = token("heroEnd")
    static let onHero = token("onHero")

    static var heroGradient: LinearGradient {
        LinearGradient(colors: [heroStart, heroMid, heroEnd], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

extension UIColor {
    convenience init(hex: String) {
        var value: UInt64 = 0
        Scanner(string: hex.trimmingCharacters(in: ["#"])).scanHexInt64(&value)
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255.0,
            green: CGFloat((value >> 8) & 0xFF) / 255.0,
            blue: CGFloat(value & 0xFF) / 255.0,
            alpha: 1
        )
    }
}

/// D-127: the app's primary action, because it did not have one.
///
/// Every call to action shipped as `.buttonStyle(.bordered)` (the
/// system's low-contrast pill), so on the Recovery Kit screen "I've
/// written it down" carried the same visual weight as the twelve grey
/// word chips beside it, and the founder reported the buttons as easy to
/// miss. ADR-0023's accent token existed and no primary action used it.
///
/// Filled, full width, accent ground, page colour for the label. §11.7:
/// the label scales with Dynamic Type and the container grows with it
/// rather than clipping.
struct PrimaryButtonStyle: ButtonStyle {
    var tint: Color = Tokens.accent
    /// D-221 (Design 10): the small trailing button of the next cards (the Look Book's `.btn`): not full width, the
    /// label at 15, padding 16 by 10; the same looks, shape and disabled alpha. Android's twin is `PrimaryAction(compact)`.
    var compact = false
    /// D-221 (S3, SPEC 11.7): the compact button's touch height; its drawing keeps to the label (about 40).
    static let minTouch: CGFloat = 44

    func makeBody(configuration: Configuration) -> some View {
        PrimaryButtonBody(configuration: configuration, tint: tint, compact: compact)
    }
}

/// D-128: `.disabled` does NOT dim a custom ButtonStyle. The style draws
/// every pixel, and `@Environment` read in the style struct itself never
/// updates. D-127 papered over that with a manual `.opacity` on one
/// Confirm button; every other disabled primary looked tappable and did
/// nothing. Reading `isEnabled` inside a View is the fix for all of them.
private struct PrimaryButtonBody: View {
    let configuration: ButtonStyle.Configuration
    let tint: Color
    let compact: Bool
    @Environment(\.isEnabled) private var isEnabled

    /// D-221: the compact button keeps to its label; the full one fills the width.
    private var maxWidth: CGFloat? { compact ? nil : .infinity }
    private var labelSize: CGFloat { compact ? 15 : 17 }

    var body: some View {
        if ButtonLook.of(AppearanceState.shared.theme) == .outlined {
            // D-213 (the founder, 2026-10-15): Vardr Labs' main button is outlined: a 1 pt border and the words
            // in the tint, IBM Plex Mono uppercase at .08 em, square (art.py measures words and border).
            configuration.label
                .font(TypeRole.label(labelSize))
                .textCase(ButtonLook.labelCase(AppearanceState.shared.theme, keepsCase: false))
                .tracking(ButtonLook.tracking(labelSize))
                .frame(maxWidth: maxWidth)
                .padding(.vertical, compact ? 10 : 15)
                .padding(.horizontal, compact ? 16 : 18)
                .contentShape(Rectangle())
                .foregroundStyle(tint)
                .background(tint.opacity(configuration.isPressed ? 0.12 : 0))
                .overlay(Rectangle().strokeBorder(tint, lineWidth: 1))
                // D-234 (L13): dimmed once, as a group, as Android's `alpha` dims the finished button.
                .compositingGroup()
                .opacity(isEnabled ? 1 : ButtonLook.disabledAlpha)
                .compactTouch(compact)
        } else {
            // D-174 (ADR-0038): taller, rounder and a weight heavier; the label
            // is on-accent, measured against the fill in every mode.
            configuration.label
                .font(TypeRole.label(labelSize))
                .frame(maxWidth: maxWidth)
                .padding(.vertical, compact ? 10 : 15)
                .padding(.horizontal, compact ? 16 : 18)
                .background(tint.opacity(configuration.isPressed ? 0.8 : 1))
                .foregroundStyle(Tokens.onAccent)
                .clipShape(ThemeArt.controlShape(AppearanceState.shared.theme))
                // D-234 (L13): dimmed once, as a group: the label over the fill, then both at the disabled alpha
                // (Android's look); per layer the label sank into the dimmed fill.
                .compositingGroup()
                .opacity(isEnabled ? 1 : ButtonLook.disabledAlpha)
                .compactTouch(compact)
        }
    }
}

private extension View {
    /// D-221 (S3): a compact button is laid out at least `PrimaryButtonStyle.minTouch` tall around its drawing (which
    /// keeps to the label), and the whole frame takes the touch. A full button is already taller than the minimum.
    func compactTouch(_ compact: Bool) -> some View {
        frame(minHeight: compact ? PrimaryButtonStyle.minTouch : nil).contentShape(Rectangle())
    }
}

/// The secondary action: a hairline, not a fill. SPEC §11.1 names
/// hairline rules as the primary structural device, and this is where
/// that belongs: a real affordance that still cannot be mistaken for
/// the primary one.
struct SecondaryButtonStyle: ButtonStyle {
    /// D-176: the Danger zone's actions take the owe coral, not the accent.
    var destructive = false
    /// D-213: an identifier (an @username suggestion) keeps its case in Vardr Labs; the face and tracking stay.
    var keepsCase = false

    func makeBody(configuration: Configuration) -> some View {
        SecondaryButtonBody(configuration: configuration, destructive: destructive, keepsCase: keepsCase)
    }
}

private struct SecondaryButtonBody: View {
    let configuration: ButtonStyle.Configuration
    var destructive = false
    var keepsCase = false
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        if ButtonLook.of(AppearanceState.shared.theme) == .outlined {
            // D-213: Vardr Labs' second action is plain text in the accent (or the owe colour).
            configuration.label
                .font(TypeRole.label(17))
                .textCase(ButtonLook.labelCase(AppearanceState.shared.theme, keepsCase: keepsCase))
                .tracking(ButtonLook.tracking(17))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .padding(.horizontal, 18)
                .contentShape(Rectangle())
                .foregroundStyle(destructive ? Tokens.owed : Tokens.accent)
                .opacity(isEnabled ? (configuration.isPressed ? 0.6 : 1) : ButtonLook.disabledAlpha)
        } else {
            // D-174 (ADR-0038): tonal, not a hairline outline: the accent's
            // soft fill reads as a button without competing with the primary.
            configuration.label
                .font(TypeRole.label(17))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .padding(.horizontal, 18)
                .foregroundStyle(destructive ? Tokens.owed : Tokens.accent)
                .background(
                    (destructive ? Tokens.owedSoft : Tokens.accentSoft).opacity(configuration.isPressed ? 0.7 : 1),
                    in: ThemeArt.controlShape(AppearanceState.shared.theme)
                )
                // D-234 (L13): dimmed once, as a group.
                .compositingGroup()
                .opacity(isEnabled ? 1 : ButtonLook.disabledAlpha)
        }
    }
}

/// D-139: a screen's main action sits UNDER the form, not in it. iOS 26 and
/// later clip a clear-background List/Form row to a pill: a primary button
/// placed as a row rendered as a capsule, and a secondary button's outline
/// was erased (D-135, seen on iOS 26.5 and 27). A bottom bar is also the
/// system's own place for a form's commit action. On iOS 26+ `safeAreaBar`
/// gives it the system's scroll-edge treatment as the form scrolls beneath
/// (system chrome, ADR-0023); older systems get a plain inset on the page
/// colour, so rows never show through the button.
private struct FormActionBar<Bar: View>: ViewModifier {
    let bar: Bar

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.safeAreaBar(edge: .bottom) { padded }
        } else {
            content.safeAreaInset(edge: .bottom) { padded.background(Tokens.bg) }
        }
    }

    private var padded: some View {
        bar
            // A disabled primary button is drawn at 45% opacity; without a
            // backing, the rows scrolling under the bar showed through it
            // (seen on iOS 27: "Priya" inside a dimmed "Add expense").
            .background(Tokens.bg, in: ThemeArt.controlShape(AppearanceState.shared.theme))
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
    }
}

extension View {
    /// The screen's main action, in a bar under the form (D-139).
    func formActionBar<Bar: View>(@ViewBuilder _ bar: () -> Bar) -> some View {
        modifier(FormActionBar(bar: bar()))
    }
}

/// The kit's confirm fields rendered as bare placeholder text with no
/// box or underline, which is why they read as labels rather than
/// inputs. A hairline underline is the smallest treatment that says
/// "type here" without inventing a control the design system does not
/// have.
struct HairlineFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.vertical, 10)
            .overlay(alignment: .bottom) {
                Rectangle().fill(Tokens.hairline).frame(height: 1)
            }
    }
}

extension View {
    func hairlineField() -> some View { modifier(HairlineFieldStyle()) }
}

// MARK: - D-176: Aurora (ADR-0038 revised, the founder, 2026-09-26)

/// Aurora's shared values. The page ground (the curtains over dawn or night) is PageArt's (D-214).
enum Aurora {
    /// D-180: the white film under the hero's chips. At 18% white text fell
    /// to 4.35:1 over the rose stop; 12% keeps 4.9:1 over every stop
    /// (.drafts/d180/chips.py; AppearanceTests measures it).
    static let heroChipOpacity = 0.12
}

struct AuroraBackdrop: View {
    /// D-214: the theme's ground, drawn by PageArt (Aurora's curtains over dawn or night, Norse's runes, Vardr Labs' grid).
    var body: some View { PageArt() }
}

/// Frosted glass for a card over the Aurora backdrop: the system's thin
/// material with the surface colour laid over it, so text keeps the contrast
/// measured against the surface (a whisper of the glow shows through).
struct GlassCard: ViewModifier {
    var radius: CGFloat? = nil
    var padding: CGFloat = 16
    /// D-213: a wash on the card's own shape, between its fill and its strokes, so a tinted card (a group's
    /// header) keeps Norse's notches and edge and Vardr Labs' frame marks on top.
    var tint: Color? = nil

    /// D-212: the theme's radius; Aurora keeps a radius a call site asks for.
    static func resolvedRadius(_ asked: CGFloat?, theme: AppTheme) -> CGFloat {
        theme.hasGlass ? (asked ?? theme.cardRadius) : theme.cardRadius
    }

    func body(content: Content) -> some View {
        let theme = AppearanceState.shared.theme
        let r = Self.resolvedRadius(radius, theme: theme)
        let shape = RoundedRectangle(cornerRadius: r, style: .continuous)
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                switch CardStyle.of(theme) {
                case .glass:
                    // D-226 (L36): the film, the rim and the shadow from the art table (iOS is the reference: the same
                    // values as before, so no pixel moves; Android's glass reads the same keys). The rim lies inside.
                    shape.fill(.ultraThinMaterial)
                        .overlay(shape.fill(Tokens.surface.opacity(Double(ThemeArt.m("glassFilm")))))
                        .overlay(shape.fill(tint ?? .clear))
                        .overlay(shape.strokeBorder(Color.white.opacity(Double(ThemeArt.m("glassRim"))), lineWidth: 1).blendMode(.overlay))
                        .shadow(color: Color.black.opacity(Double(ThemeArt.m("glassShadowAlpha"))),
                                radius: ThemeArt.m("glassShadowSigma"), y: ThemeArt.m("glassShadowY"))
                case .notched:
                    // D-213: Norse's notched corners on the Look Book's dark gradient and gold-brown edge. D-226 (L35,
                    // Q18 a, D28): the edge on the four straight sides only, 1 pt inside the shape and clipped to it, so
                    // the corners are cut clean as the Look Book's clip-path cuts its card (no line on the diagonals).
                    let notch = NotchedRectangle(chamfer: ThemeArt.m("chamfer"))
                    notch.fill(LinearGradient(colors: [ThemeArt.norseCardTop, ThemeArt.norseCardBottom], startPoint: .top, endPoint: .bottom))
                        .overlay(notch.fill(tint ?? .clear))
                        .overlay(NotchEdges(chamfer: ThemeArt.m("chamfer")).stroke(ThemeArt.norseEdge, lineWidth: 1).clipShape(notch))
                        // D-243 (T7): the static gold glow outside the edge, beneath the fill (ThemeArt.swift's NorseEdgeGlow).
                        .background { NorseEdgeGlow() }
                case .framed:
                    // D-213: Vardr Labs' square card with a frame mark at each corner.
                    Rectangle().fill(Tokens.surface)
                        .overlay(Rectangle().fill(tint ?? .clear))
                        .overlay(Rectangle().strokeBorder(Tokens.hairline, lineWidth: 1))
                        .overlay(FrameMarks(inset: ThemeArt.m("frameInset"), length: ThemeArt.m("frameLength"))
                            .stroke(Tokens.textDim.opacity(Double(ThemeArt.m("frameAlpha"))), lineWidth: 1))
                }
            }
    }
}

extension View {
    /// D-176: a frosted card; D-212: the corners and the glass follow the theme.
    func glassCard(radius: CGFloat? = nil, padding: CGFloat = 16, tint: Color? = nil) -> some View {
        modifier(GlassCard(radius: radius, padding: padding, tint: tint))
    }

    /// D-176: the theme's ground behind a scrolling screen: the Aurora glow, or the plain page colour (D-212).
    func auroraBackground() -> some View {
        background { AuroraBackdrop() }
    }

    /// D-176: a Form or List over the theme's ground (the glow, or the plain page colour): its grey grouped ground hidden,
    /// its rows left as the system's cards.
    func auroraFormBackground() -> some View {
        scrollContentBackground(.hidden).background { AuroraBackdrop() }
    }
}
