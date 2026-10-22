import SwiftUI

/// The account surface's front door (D-126): twin of Android's
/// `AccountHubScreen.kt`.
///
/// WHAT IT MAY NOT DO, and this is the constraint that shapes it:
/// GUARANTEES #5 says an account is opt-in and is **never created,
/// implied, or nudged for**. So the no-account state is written as a
/// statement of fact, not an invitation: it says the app works without an
/// account, because it does, and offers sign-in as a plain affordance
/// with no benefit copy, no badge, no "unlock", and nothing that reads as
/// a reason to want one. A user who never signs in should be able to open
/// this screen and feel finished, not sold to.
///
/// WHAT LIVES HERE vs ON DEVICES: SPEC §3.5 requires the device list to
/// be "a first-class screen, not a settings sub-page", so Devices opens
/// as its own full screen rather than nesting inside this one. The sync
/// switch moved here from the device list (D-125 put it there because no
/// account surface existed yet): sync is an account-level fact, the
/// device list is about devices.
///
/// ENTRY POINT: both shells now reach this from an account glyph on the
/// Balance screen. D-126 recorded iOS as the exception (its root was
/// still `SmokeScreenView`, a developer menu), and D-128's `HomeShell`
/// closed that: the glyph sits in Balance's toolbar and pushes this
/// screen, iOS's natural equivalent of Android's route over the tabs.

/// D-155: a plain sign-out. Twin of Android's `AccountSignOut`.
enum AccountSignOut {
    static let note =
        "Signing out stops sync on this device. Your ledger stays here, and signing "
        + "in again picks up where you left off."
}

/// D-225 (Design 5): Your data's export row (the words the old card used). Android's twin is `YourDataCopy`.
enum YourDataCopy {
    static let export = "Export your data"
    static let exportDetail = "Your whole ledger in one file: CSV to read, or a JSON backup to restore."
}

/// D-206 (the founder's feedback item 4, 2026-10-13): You, in five groups
/// instead of one long page. The map says where each setting lives; the
/// screens render from it, so the tested map is the layout. Account settings
/// are absent without an account (GUARANTEES #5). Android's twin is in
/// AccountHubScreen.kt.
enum SettingsSection: String, CaseIterable {
    case profileAccount, appearance, security, data, about

    var title: String {
        switch self {
        case .profileAccount: return "Profile and account"
        case .appearance: return "Appearance"
        case .security: return "Security"
        case .data: return "Your data"
        case .about: return "About SKIPTI"
        }
    }

    /// D-222 (L56): the Lucide drawing in the row's icon tile (Android's `glyphName` names the same drawings).
    var glyph: LucideGlyph {
        switch self {
        case .profileAccount: return .userRound
        case .appearance: return .sun
        case .security: return .shield
        case .data: return .download
        case .about: return .info
        }
    }
}

/// D-222 (L58): the facts You's row summaries read.
struct YouRowFacts: Equatable {
    var signedIn: Bool
    var methodLabel: String?
    var phoneCount: Int?
    var theme: AppTheme
    var mode: AppearanceMode
    var lockTimeout: LockTimeout
    var lockAvailable: Bool
    var version: String
    /// D-254: the account's approval; waiting or not approved stands in for the method and phones.
    var approval: ApprovalState = .unknown
}

/// D-222 (L58): each of You's rows' summary as parts, joined on screen by the theme's separator
/// (`Separator.text(parts, size: TypeScale.detail)`, Norse's gold rune) and spoken as "title, part, part" (the separator
/// is never read aloud). One table on both shells (Android's twin in AccountHubScreen.kt).
enum YouRows {
    static let promisesWord = "promises"
    static let licencesWord = "licences"
    static let noPhoneLock = "App lock needs a phone lock"

    static func parts(_ section: SettingsSection, _ facts: YouRowFacts) -> [String] {
        switch section {
        case .profileAccount:
            guard facts.signedIn else { return [YouCopy.noAccount] }
            // D-254: "Waiting for approval" or "Not approved" in place of "Google · 1 phone".
            if let approval = ApprovalCopy.accountSummary(facts.approval) { return [approval] }
            // A failed or unread count (nil) and a count of 0 are left out: never "0 phones".
            let count = facts.phoneCount.flatMap { $0 >= 1 ? PhonesCopy.count($0) : nil }
            return [facts.methodLabel ?? YouCopy.signedIn] + (count.map { [$0] } ?? [])
        case .appearance:
            return [facts.theme.label, ModeChoice.shown(theme: facts.theme, stored: facts.mode).chosen.label]
        case .security:
            return [securityLine(facts.lockTimeout, available: facts.lockAvailable)]
        case .data:
            return [YouCopy.dataLine]
        case .about:
            // The build number stays on About's own page: it differs per shell (CLAUDE.md 10.3).
            return [facts.version, promisesWord, licencesWord]
        }
    }

    static func spoken(title: String, parts: [String]) -> String {
        ([title] + parts).joined(separator: ", ")
    }

    /// One wording on both shells; the Security page's own line keeps each platform's words.
    static func securityLine(_ timeout: LockTimeout, available: Bool) -> String {
        guard available else { return noPhoneLock }
        switch timeout {
        case .immediately: return "App lock immediately"
        case .oneMinute: return "App lock after 1 minute"
        case .twoMinutes: return "App lock after 2 minutes"
        case .fiveMinutes: return "App lock after 5 minutes"
        case .fifteenMinutes: return "App lock after 15 minutes"
        case .off: return "App lock off"
        }
    }
}

/// D-222 (L62): what the mode control shows. Norse is always dark: Dark chosen and the control disabled, the stored
/// choice untouched (it returns with another theme); the other themes show the stored mode, enabled.
struct ModeChoice: Equatable {
    let chosen: AppearanceMode
    let enabled: Bool

    static func shown(theme: AppTheme, stored: AppearanceMode) -> ModeChoice {
        theme.forcesDark ? ModeChoice(chosen: .dark, enabled: false) : ModeChoice(chosen: stored, enabled: true)
    }
}

/// D-222 (L63): the drawn text-size slider's geometry over `TextSize.steps`. The thumb's centre runs from thumb / 2 to
/// width - thumb / 2; a touch at x picks floor(v + 0.5), clamped (half up on both shells: Swift's `.rounded()` is half
/// away from zero and differs at -1.5, so it is not used). Goldens: art.py section 29.
enum StepSlider {
    static let lo = TextSize.steps.lowerBound
    static let hi = TextSize.steps.upperBound

    static func clamped(_ step: Int) -> Int { min(max(step, lo), hi) }

    static func step(atX x: CGFloat, width: CGFloat, thumb: CGFloat) -> Int {
        let travel = Double(width - thumb)
        guard travel > 0 else { return lo }
        let v = Double(lo) + Double(x - thumb / 2) * Double(hi - lo) / travel
        return clamped(Int(floor(v + 0.5)))
    }

    static func centre(step: Int, width: CGFloat, thumb: CGFloat) -> CGFloat {
        thumb / 2 + CGFloat(clamped(step) - lo) * (width - thumb) / CGFloat(hi - lo)
    }

    /// VoiceOver's swipe up and down: one step, clamped at both ends.
    static func adjusted(_ step: Int, by delta: Int) -> Int { clamped(step + delta) }
}

/// D-234 (A1, the founder's): Appearance's live sample line, "Lunch with Luna" (decision 227, D-253: no brand in public
/// copy) and 48 US dollars for everyone,
/// whatever the default currency; converted to minor units by core's currency exponent and formatted by core in the
/// phone's locale (`$48.00` in both locales core knows). Nothing here formats a number (a test reads this body).
/// Android's twin is `SampleLine` in AccountHubScreen.kt.
enum SampleLine {
    static let title = "Lunch with Luna"
    static let wholeAmount = "48"
    static let currency = "USD"

    static func parts(amount: String) -> [String] { [title, amount] }

    /// What VoiceOver reads: the parts joined by a comma, never the theme's separator (Android's contentDescription).
    static func spoken(amount: String) -> String { parts(amount: amount).joined(separator: ", ") }

    static func amount(locale: LocaleTag = AppLocale.current) -> String {
        guard let minor = try? ffiMoneyParseMinor(digits: wholeAmount, currencyCode: currency),
              let text = try? ffiMoneyFormat(minorUnits: minor, currencyCode: currency, locale: locale) else { return "" }
        return text
    }
}

/// D-212: the Appearance page's words (twins with Android's AppearanceCopy).
enum AppearanceCopy {
    static let cards = ["Theme", "Light or dark", "Text size"]
    static let norseAlwaysDark = "Norse is always dark."
    static let textSizeCaption = "Starts at your phone's size; changes SKIPTI only."
    /// D-215 fix (F2): Norse's line under the mode control and the text-size caption, in points (Android: DETAIL_SIZE, sp).
    static let detailSize: CGFloat = 14
}

/// D-222 (L60, L61): one theme's card on Appearance's ground: the current theme's control shape (Aurora 14, Norse
/// notched, Vardr Labs square) with a 2 pt border (`accent` chosen, `hairline` not), `themePad` inside, the swatch then the
/// name `themeGap` apart. A button with the selected trait, as before.
struct ThemeCard: View {
    let theme: AppTheme
    let selected: Bool
    let choose: () -> Void

    var body: some View {
        let shape = ThemeArt.controlShape(AppearanceState.shared.theme)
        Button(action: choose) {
            VStack(spacing: ThemeArt.m("themeGap")) {
                ThemeSwatch(theme: theme)
                Text(verbatim: theme.label).font(TypeRole.name(14)).foregroundStyle(Tokens.text)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(ThemeArt.m("themePad"))
            .frame(maxWidth: .infinity)
            // A 2 pt border inside the shape: the 4 pt stroke is centred on the outline and clipped to it.
            .overlay { shape.stroke(selected ? Tokens.accent : Tokens.hairline, lineWidth: 4).clipShape(shape) }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: theme.label))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// D-222 (L60, Q9 a): a theme's swatch draws that theme's OWN dark art, still, whatever the mode or the phone: Aurora
/// the night sky with the hero's stars and curtains; Norse its ground, a 1 pt inner border and ᚠᚷᛗᛏ in its gold (in
/// every theme: it is Norse's own preview); Vardr Labs its dark ground, the dot grid and the frame marks. Colours by token
/// name in the fixed sets, never a new hex. Clipped to the current theme's panel shape. Decorative.
struct ThemeSwatch: View {
    let theme: AppTheme

    /// A token's colour in one fixed set (the swatch never follows the page's set).
    static func hex(_ name: String, _ set: ThemeSet) -> String { Tokens.hex(Tokens.palette[name]!, set) }

    private static func color(_ name: String, _ set: ThemeSet) -> Color { Color(UIColor(hex: hex(name, set))) }

    var body: some View {
        let clip = ThemeArt.panelShape(AppearanceState.shared.theme, radius: ThemeArt.m("swatchRadius"))
        ZStack {
            switch theme {
            case .aurora:
                NightSky()
                Canvas { ctx, size in
                    ArtDraw.curtains(&ctx, size, Curtains.still, night: true,
                                     curtainStrength: Strength.of("auroraHeroCurtains"), starStrength: Strength.of("auroraHeroStars"))
                }
            case .norse:
                Self.color("bg", .norse)
                // Centred on the outline at 2 and clipped: 1 pt inside, following the panel's notch or corners.
                clip.stroke(Self.color("accent", .norse), lineWidth: 2)
                Text(verbatim: Runes.balance + Runes.expenses + Runes.groups + Runes.settle)
                    .font(TypeRole.rune(ThemeArt.m("swatchRune")))
                    .tracking(ThemeArt.m("swatchRune") * ThemeArt.m("swatchRuneTracking"))
                    .foregroundStyle(Self.color("accent", .norse))
                    .lineLimit(1)
                    .fixedSize()
            case .vardr:
                Self.color("bg", .vardrDark)
                Canvas { ctx, size in
                    let r = ThemeArt.m("swatchGridDot")
                    var dots = Path()
                    for d in ArtGeometry.gridDots(size, pitch: ThemeArt.m("swatchGridPitch")) {
                        dots.addEllipse(in: CGRect(x: d.x - r, y: d.y - r, width: r * 2, height: r * 2))
                    }
                    ctx.fill(dots, with: .color(Self.color("ring", .vardrDark)))
                }
                FrameMarks(inset: ThemeArt.m("swatchMarkInset"), length: ThemeArt.m("swatchMarkLength"))
                    .stroke(Self.color("accent", .vardrDark), lineWidth: 1)
            }
        }
        .frame(height: ThemeArt.m("swatchHeight"))
        .frame(maxWidth: .infinity)
        .clipShape(clip)
        .accessibilityHidden(true)
    }
}

/// D-222 (L62, L66): Appearance's System / Light / Dark, drawn the same on both phones: a `touchTarget` touch row holding
/// the `modeTrack` track in `accentSoft`, shaped by the theme's pill (Aurora capsule, Norse notched, Vardr Labs square),
/// three equal segments, the chosen one inset `modeInset` in `surface` with a soft shadow; words through the name role
/// at `TypeScale.segment`, the chosen in `text`, the others dimmed. The segments sit `modeInset` inside the track's ends
/// and the chosen pill fills its segment, `(W - 2 inset) / 3` (fix wave X1: Android's and the Look Book's). Each segment's
/// touch area is the whole row's height and its third. Norse shows Dark chosen and the control disabled
/// (`ModeChoice.shown`), dimmed ONCE as a group like Android's `alpha` (fix wave X2: a compositing group, and segments
/// whose style never dims them again). VoiceOver reads a real segmented Picker (the representation). Android's twin is
/// `ModeControl` in AccountHubScreen.kt.
struct ModeControl: View {
    let theme: AppTheme
    @Binding var stored: AppearanceMode
    /// D-250: the chosen pill slides to the next segment (one pill, `RowsMotion`, the switch's own travel).
    @Namespace private var pill

    var body: some View {
        let shown = ModeChoice.shown(theme: theme, stored: stored)
        let shape = ThemeArt.pillShape(theme)
        let inset = ThemeArt.m("modeInset")
        // The touch row's margin around the track: (48 - 40) / 2.
        let margin = (ThemeArt.m("touchTarget") - ThemeArt.m("modeTrack")) / 2
        HStack(spacing: 0) {
            ForEach(AppearanceMode.allCases) { mode in
                let chosen = mode == shown.chosen
                Button { stored = mode } label: {
                    Text(verbatim: mode.label)
                        .font(TypeRole.name(TypeScale.segment))
                        .foregroundStyle(chosen ? Tokens.text : Tokens.textDim)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.horizontal, inset)
                        .padding(.vertical, margin + inset)
                        .frame(maxWidth: .infinity, minHeight: ThemeArt.m("touchTarget"))
                        .background {
                            if chosen {
                                shape.fill(Tokens.surface)
                                    .shadow(color: .black.opacity(ThemeArt.m("segmentShadowAlpha")),
                                            radius: ThemeArt.m("segmentShadowRadius"), y: ThemeArt.m("segmentShadowY"))
                                    .padding(.vertical, margin + inset)
                                    .matchedGeometryEffect(id: "chosen", in: pill)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(SegmentButtonStyle())
            }
        }
        .padding(.horizontal, inset)
        .background { shape.fill(Tokens.accentSoft).padding(.vertical, margin) }
        .rowsMotion(shown.chosen)
        .disabled(!shown.enabled)
        .compositingGroup()
        .opacity(shown.enabled ? 1 : ButtonLook.disabledAlpha)
        .accessibilityRepresentation {
            Picker(AppearanceCopy.cards[1], selection: Binding(get: { shown.chosen }, set: { stored = $0 })) {
                ForEach(AppearanceMode.allCases) { mode in
                    Text(mode.label).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .disabled(!shown.enabled)
        }
    }
}

/// D-222 fix wave (X2): a mode segment's button draws its label as it is: no press or disabled dimming of its own (the
/// plain style dims a disabled label, which dimmed Norse's words twice under the control's group alpha).
private struct SegmentButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

/// D-222 (L63, L65): the text-size slider, drawn the same on both phones over `TextSize.steps`: in a `touchTarget` row,
/// the `sliderTrack` track in `ringTrack` and its filled part to the thumb's centre in `accent`, both in the theme's meter
/// shape; the `sliderThumb` thumb in the person tile's shape (circle, diamond, square), `surface` ringed `sliderRing` in
/// `accent`. A tap or drag anywhere on the row picks `StepSlider.step(atX:)`; only a new step is written. The two A's
/// are fixed sizes in `text`. VoiceOver reads a real Slider whose swipes move one step. Android's twin is
/// `TextSizeSlider` in AccountHubScreen.kt.
struct TextSizeSlider: View {
    @Binding var step: Int

    var body: some View {
        let current = TextSize.parse(step)
        let thumb = ThemeArt.m("sliderThumb")
        HStack(spacing: 12) {
            // Fixed sizes on purpose: the two A's show the range, they do not scale.
            Text(verbatim: "A").font(TypeRole.bodyFixed(TypeScale.rangeSmall)).foregroundStyle(Tokens.text).accessibilityHidden(true)
            GeometryReader { geo in
                let width = geo.size.width
                SliderBar(step: current, width: width)
                .frame(width: width, height: geo.size.height)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                    let next = StepSlider.step(atX: value.location.x, width: width, thumb: thumb)
                    if next != current { step = next }
                })
            }
            .frame(height: ThemeArt.m("touchTarget"))
            Text(verbatim: "A").font(TypeRole.bodyFixed(TypeScale.rangeLarge, weight: 700)).foregroundStyle(Tokens.text).accessibilityHidden(true)
        }
        .accessibilityRepresentation {
            Slider(value: Binding(get: { Double(current) }, set: { step = StepSlider.clamped(Int($0.rounded())) }),
                   in: Double(StepSlider.lo)...Double(StepSlider.hi), step: 1)
                .accessibilityLabel(Text(verbatim: AppearanceCopy.cards[2]))
                .accessibilityValue(Text(verbatim: TextSize.accessibilityValue(current)))
                .accessibilityAdjustableAction(adjust)
        }
    }

    /// VoiceOver's swipe up and down: one step, clamped at both ends.
    private func adjust(_ direction: AccessibilityAdjustmentDirection) {
        let current = TextSize.parse(step)
        switch direction {
        case .increment: step = StepSlider.adjusted(current, by: 1)
        case .decrement: step = StepSlider.adjusted(current, by: -1)
        @unknown default: break
        }
    }
}

/// D-222 fix wave (X3): the slider's track, its filled part and the thumb, drawn at a known width (tests measure it).
/// The `sliderTrack` track in `ringTrack` runs between the thumb's two end centres and the fill in `accent` from the
/// first of them to the thumb's centre, both in the theme's meter shape, so at either end the track's end and the fill's
/// start lie under the thumb (no gold beside Norse's diamond at the smallest step, no track past its tip at the largest).
/// The `sliderThumb` thumb is the person tile's shape filled `surface`, its `sliderRing` ring in `accent` a stroke
/// centred on that shape inset by half the ring (one geometry on both phones: Norse's diamond 18.59 tip to tip, the ring
/// 2.5 through the tip). Android's twin is drawn in `TextSizeSlider`.
struct SliderBar: View {
    let step: Int
    let width: CGFloat

    var body: some View {
        let theme = AppearanceState.shared.theme
        let thumb = ThemeArt.m("sliderThumb")
        let ring = ThemeArt.m("sliderRing")
        let track = ThemeArt.meterShape(theme)
        // The thumb is the person tile's shape at its 20 pt footprint (a Norse diamond spans 20 across its corners).
        let knob = TileClip.shape(TileShape.of(theme, person: true), size: thumb)
        let x = StepSlider.centre(step: step, width: width, thumb: thumb)
        let start = StepSlider.centre(step: StepSlider.lo, width: width, thumb: thumb)
        let end = StepSlider.centre(step: StepSlider.hi, width: width, thumb: thumb)
        ZStack(alignment: .leading) {
            track.fill(Tokens.ringTrack).frame(width: end - start, height: ThemeArt.m("sliderTrack")).offset(x: start)
            track.fill(Tokens.accent).frame(width: x - start, height: ThemeArt.m("sliderTrack")).offset(x: start)
            ZStack {
                knob.fill(Tokens.surface)
                knob.stroke(Tokens.accent, lineWidth: ring).padding(ring / 2)
            }
            .frame(width: thumb, height: thumb)
            .offset(x: x - thumb / 2)
        }
        .frame(width: width, height: ThemeArt.m("touchTarget"), alignment: .leading)
    }
}

/// D-222 (L56): a You row's icon tile: the theme's group tile (`TileShape.of(theme, person: false)` through `TileClip`:
/// Aurora rounded, Norse the diamond at its drawn side with the gold-brown edge, overflowing into the row's padding,
/// Vardr Labs square) on a `settingsTile` footprint in `accentSoft`, the Lucide glyph upright in `accent` at
/// `settingsGlyph`. Decorative. Android's twin is `SettingsTile`.
struct SettingsTile: View {
    let glyph: LucideGlyph

    var body: some View {
        let kind = TileShape.of(AppearanceState.shared.theme, person: false)
        let size = ThemeArt.m("settingsTile")
        let shape = TileClip.shape(kind, size: size)
        let drawn = TileClip.drawnSide(kind, size: size)
        LucideIcon(glyph: glyph, size: ThemeArt.m("settingsGlyph"))
            .foregroundStyle(Tokens.accent)
            .frame(width: drawn, height: drawn)
            .background(Tokens.accentSoft, in: shape)
            .overlay { TileClip.edge(kind) }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// D-222 (L56 to L58): one row of You's list: the section's tile, the title over its summary parts joined by the
/// theme's separator (Norse's gold ᛫), the chevron. D-225 (Design 1): a `ListRow` with a tile and a chevron, so You's
/// list, Haptics and every settings page draw one row. Android's twin is `SettingsRow`.
struct SettingsRow: View {
    let glyph: LucideGlyph
    let title: String
    let parts: [String]

    var body: some View {
        ListRow(glyph: glyph, title: title, parts: parts, trailing: .chevron)
    }
}

/// D-224 (L48, Design 10): Appearance's Haptics row: one list card holding the vibrate tile, "Haptics" over "A tap when
/// something is done", and the switch (`appearance.haptics`, on until turned off). The whole row toggles it; turning it
/// on plays one success haptic, so the person feels what they turned on. One element to VoiceOver: the switch, named
/// by the title and the summary. D-225 (Design 3): a `.toggle` `ListRow` with the drawn `ThemeSwitch`. Android's twin
/// is `HapticsRow` in AccountHubScreen.kt.
struct HapticsRow: View {
    @AppStorage(HapticsSetting.storageKey) private var on = true

    /// The haptic a change plays: success when it is turned on, none when it is turned off.
    static func feedback(turnedOn: Bool) -> HapticKind? { turnedOn ? .success : nil }

    private var isOn: Binding<Bool> {
        Binding(get: { on }, set: { new in
            // D-244 (ADR-0043): through the one write of a synced setting.
            SettingsSync.set(HapticsSetting.storageKey, new)
            // D-224 fix wave (M1): through the app's one success gate, as every other success (Android's twin too).
            if HapticsRow.feedback(turnedOn: new) == .success { DoneCenter.shared.success() }
        })
    }

    var body: some View {
        ListCard {
            ListRow(glyph: .vibrate, title: DoneCopy.hapticsTitle, parts: [DoneCopy.hapticsSummary], trailing: .toggle(isOn))
        }
    }
}

/// D-231 (decision 69): Appearance's Less motion row, the twin of `HapticsRow`: one list card holding the sparkles tile,
/// the title over the summary (`MotionCopy`), and the switch on `appearance.lessMotion`. Nothing stored reads as the
/// low-memory default (`MotionSetting.parse`), so a low-memory phone shows it on; a tap stores an explicit value that wins
/// from then on. The root applier watches the same key. No haptic of its own. One element to VoiceOver: the switch,
/// named by the title and the summary (the `.toggle` row). Android's twin is `MotionRow` in AccountHubScreen.kt.
struct MotionRow: View {
    @AppStorage(MotionSetting.storageKey) private var stored: Bool?

    private var isOn: Binding<Bool> {
        // D-244 (ADR-0043): a tap stores an explicit value through the one write of a synced setting.
        Binding(get: { MotionSetting.parse(stored: stored, lowRam: MotionSetting.lowRamAtLaunch) }, set: { SettingsSync.set(MotionSetting.storageKey, $0) })
    }

    var body: some View {
        ListCard {
            ListRow(glyph: .sparkles, title: MotionCopy.title, parts: [MotionCopy.summary], trailing: .toggle(isOn))
        }
    }
}

/// D-216 (Design 5), D-234 (A2, decision 80): Appearance's App icon section: ONE list card whose header row reads "App
/// icon", the current choice and the chevron (`.disclosure`); a tap opens the four rows inline under it (the Match theme
/// switch row, then the three icon rows with the check on `AppIconPlan.checked`), a hairline before each, and a second
/// tap closes them. Open, the caption sits 8 under the card. The rows fade in while the card grows and the chevron turns,
/// over the switch's travel, only when art may move; under Less motion or Reduce Motion they snap (D-239: power saving does not). Closed each
/// time the page opens. A tap on an icon row stores that icon (Match theme turns off); the switch stores `match` on, and
/// the theme's own icon off (nothing visible changes). No row is ever disabled. Storing changes nothing on the phone:
/// the icon changes when the person leaves the page (`AppIconSwitcher`). Spoken: the header "App icon, <choice>,
/// collapsed" as a button, the switch "Match theme", each row "<Theme> icon" with the selected state. Android's twin is
/// `AppIconSection` in AccountHubScreen.kt.
struct AppIconSection: View {
    @AppStorage(AppIconChoice.storageKey) private var iconRaw = AppIconChoice.match.rawValue
    @AppStorage(AppTheme.storageKey) private var themeRaw = AppTheme.aurora.rawValue
    @State private var expanded = false
    @Environment(\.motionReduced) private var reduceMotion

    var body: some View {
        let choice = AppIconChoice.parse(iconRaw)
        let theme = AppTheme.parse(themeRaw)
        let checked = AppIconPlan.checked(choice, theme: theme)
        let moves = MotionClock.momentTicks(reduceMotion: reduceMotion, active: true)
        VStack(alignment: .leading, spacing: 8) {
            ListCard {
                VStack(alignment: .leading, spacing: 0) {
                    Button {
                        withAnimation(AppIconPlan.expandSeconds(moves: moves).map { .easeInOut(duration: $0) }) { expanded.toggle() }
                    } label: {
                        ListRow(title: AppIconCopy.title, trailing: .disclosure(AppIconCopy.choice(choice), expanded: expanded))
                    }
                    .buttonStyle(.plain)
                    if expanded {
                        VStack(alignment: .leading, spacing: 0) {
                            Rectangle().fill(Tokens.hairline).frame(height: 1)
                            ListRow(leading: AnyView(AppIconPreview(icon: AppIcon.of(theme))), title: AppIconCopy.match,
                                    trailing: .toggle(Binding(get: { choice == .match }, set: { SettingsSync.set(AppIconChoice.storageKey, AppIconPlan.matchSwitched(on: $0, theme: theme).rawValue) })))
                            ForEach(AppIcon.allCases, id: \.self) { icon in
                                Rectangle().fill(Tokens.hairline).frame(height: 1)
                                Button {
                                    SettingsSync.set(AppIconChoice.storageKey, AppIconPlan.tap(icon).rawValue)
                                } label: {
                                    ListRow(leading: AnyView(AppIconPreview(icon: icon)), title: icon.label, trailing: .check(icon == checked))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Text(verbatim: AppIconCopy.spoken(icon)))
                                .accessibilityAddTraits(icon == checked ? .isSelected : [])
                            }
                        }
                        .transition(.opacity)
                    }
                }
            }
            if expanded {
                CardCaption(AppIconCopy.caption)
                    .transition(.opacity)
            }
        }
    }
}

/// D-216 (Design 5): an icon's preview: its own 1024 "Any" image (144 px) drawn at `iconPreview` in a rounded square of
/// `iconPreviewRadius` of its side (Apple's icon corner ratio), the SAME shape in every theme: an icon keeps its own
/// shape (S1's theme tiles would draw it as a Norse diamond). Decorative; the row speaks for it.
struct AppIconPreview: View {
    let icon: AppIcon

    var body: some View {
        let side = ThemeArt.m("iconPreview")
        Image(icon.previewAsset)
            .resizable()
            .interpolation(.high)
            .frame(width: side, height: side)
            .clipShape(RoundedRectangle(cornerRadius: side * ThemeArt.m("iconPreviewRadius"), style: .continuous))
            .accessibilityHidden(true)
    }
}

enum SettingItem: String, CaseIterable {
    case signIn, signedInAs, signOut, sync, notifications, findMe
    case theme, appearanceMode, textSize
    case appLock, privacy, phones, erase, deleteAccount
    case currency, export, importData
    case aboutPage
    case deletionPending
}

enum SettingsMap {
    static let accountOnly: Set<SettingItem> = [
        .signedInAs, .signOut, .sync, .notifications, .findMe, .phones, .deleteAccount, .deletionPending,
    ]

    static func items(_ section: SettingsSection, signedIn: Bool) -> [SettingItem] {
        switch section {
        case .profileAccount:
            // Decision 252 (the founder, 2026-10-21): how you are signed in and Sign out first (one card, `cards`), then
            // Find me (your username), then Sync and Notifications.
            return signedIn ? [.signedInAs, .signOut, .findMe, .sync, .notifications] : [.signIn]
        case .appearance:
            return [.theme, .appearanceMode, .textSize]
        case .security:
            return [.appLock, .privacy] + (signedIn ? [.phones] : []) + [.erase] + (signedIn ? [.deleteAccount] : [])
        case .data:
            return [.currency, .export, .importData]
        case .about:
            return [.aboutPage]
        }
    }

    /// Above the sections: the deletion notice, when there is an account.
    static func hubItems(signedIn: Bool) -> [SettingItem] { signedIn ? [.deletionPending] : [] }

    /// D-254 fix wave (review M3): the account controls that do nothing until the account is approved (no push reaches
    /// it, and no one can find it): away while it waits or was not approved. Sign out and Delete account stay.
    static let waitsForApproval: Set<SettingItem> = [.notifications, .findMe]

    /// Decision 252: the one card that holds how you are signed in, the sign-out line and Sign out.
    static let accountCard: [SettingItem] = [.signedInAs, .signOut]

    /// Decision 252: the page's cards, in order. `accountCard`'s two items share one card when both are shown (they are
    /// next to each other in `items`); every other item is its own card.
    static func cards(_ items: [SettingItem]) -> [[SettingItem]] {
        var cards: [[SettingItem]] = []
        for item in items {
            if let last = cards.last, last == [accountCard[0]], item == accountCard[1] {
                cards[cards.count - 1].append(item)
            } else {
                cards.append([item])
            }
        }
        return cards
    }

    /// A section's items as the page shows them for `model`'s account state.
    static func shown(_ section: SettingsSection, model: AccountHubModel) -> [SettingItem] {
        let all = items(section, signedIn: model.showsAccountControls)
        switch model.state {
        case .waiting, .notApproved: return all.filter { !waitsForApproval.contains($0) }
        case .noAccount, .active: return all
        }
    }
}

/// D-206: Home's greeting, from the phone's clock and the name on this
/// phone; nothing leaves it. Android's twin is Greeting in AccountHubScreen.kt.
enum Greeting {
    static func line(hour: Int, name: String?) -> String {
        let part = (5...11).contains(hour) ? "Good morning" : (12...16).contains(hour) ? "Good afternoon" : "Good evening"
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? part : part + ", " + trimmed
    }
}

/// Which account state the screen is in. D-192 had no "waiting for approval" (a signed-in phone simply had the
/// account); D-254 brought it back with the approval gate: a signed-in account waits until the founder approves it
/// (`waiting`), or was not approved (`notApproved`). `active` is approved, or not heard yet (the screens as before).
enum AccountState: Equatable {
    case noAccount
    case active
    case waiting
    case notApproved
}

/// Everything the hub renders, resolved.
struct AccountHubModel: Equatable {
    var state: AccountState
    var syncOn: Bool
    /// Sync and Devices exist only for an account, never as a teaser.
    var showsAccountControls: Bool
}

/// The pure part, testable without SwiftUI. D-254: a signed-in account's state follows its approval; Sign out and
/// Delete account are there in every signed-in state (leaving never waits).
func accountHubModel(signedIn: Bool, syncOn: Bool, approval: ApprovalState) -> AccountHubModel {
    guard signedIn else { return AccountHubModel(state: .noAccount, syncOn: syncOn, showsAccountControls: false) }
    let state: AccountState
    switch approval {
    case .pending: state = .waiting
    case .denied: state = .notApproved
    case .unknown, .approved: state = .active
    }
    return AccountHubModel(state: state, syncOn: syncOn, showsAccountControls: true)
}

struct AccountHubScreen: View {
    let vaultPath: String
    /// D-198: a sign-out elsewhere takes the account rows away.
    @ObservedObject private var remote = RemoteSignOut.shared

    @State private var signedIn = false
    @State private var method: SignInMethod?
    @State private var username: String?
    /// C2: nil until read; `.none` shows the card that asks for a username.
    @State private var usernameState: UsernameState?
    /// C2: the card's step, presented from the always-present ScrollView: a
    /// reload can take the card away while its sheet is open.
    @State private var pickingUsername = false
    /// D-198: when the account is to be deleted, while that is pending.
    @State private var deletionDate: Date?
    @AppStorage(AppearanceMode.storageKey) private var appearanceRaw: String?
    @AppStorage(AppTheme.storageKey) private var themeRaw = AppTheme.aurora.rawValue
    /// D-221: the setup steps' facts, nil until first read; read again when any of them can have changed.
    @State private var facts: SetupFacts?
    /// D-221 (S7): the newest facts read; an older one finishing later is dropped.
    @State private var factsReads = ReadGeneration()
    /// D-221: where the next card's "Do it" pushes (Profile, Your data, Security, Profile and account).
    @State private var pushing: StepPlace?
    /// D-221: the next card's create-group sheet, hung on the always-present ScrollView (CLAUDE.md 10.1).
    @State private var creatingGroup = false
    @AppStorage(LedgerStore.profileRevisionKey) private var profileRevision = 0
    @AppStorage(DefaultCurrency.storageKey) private var currencyRaw: String?
    @AppStorage(LockTimeout.storageKey) private var lockRaw: String?
    /// D-222 (L58): the account's phones not signed out, read from the cloud when signed in; nil unread or failed.
    @State private var phoneCount: Int?
    /// D-222 (L58): whether this phone can lock SKIPTI (it has a passcode), read on loading and on coming back. Fix wave
    /// (S8): read at once for the first frame, as Android does, so a phone with no passcode never shows a lock time first.
    @State private var lockAvailable = AppLock.available
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // D-206 (the founder's items 4 and 5): you first, then five
                // groups instead of one long page.
                YouHero(store: LedgerStore(path: vaultPath), signedIn: signedIn, username: username,
                        progress: facts.map(SetupSteps.progress))
                // C2: an account signed in before usernames were asked.
                // D-254 look fix (item 1): only once the account is approved.
                if UsernameCard.shows(usernameState, approval: Approval.shared.state) {
                    UsernameCard { pickingUsername = true }
                }
                // D-221 (L41): the first undone step; while the username card shows, it already asks.
                if let facts, let step = SetupSteps.next(facts, skipping: usernameState == UsernameState.none ? [.username] : []) {
                    NextStepCard(step: step) { go(StepPlace.of(step)) }
                }
                if SettingsMap.hubItems(signedIn: signedIn).contains(.deletionPending) {
                    DeletionPendingCard(date: $deletionDate)
                }
                // D-222 (L57): the card hugs its rows (the Look Book's list, 3 by 15). D-225: S1's card is `ListCard`.
                ListCard {
                    ForEach(SettingsSection.allCases, id: \.self) { section in
                        NavigationLink {
                            destination(section)
                        } label: {
                            row(section)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground() // D-176
        .navigationTitle(YouCopy.title)
        .navigationBarTitleDisplayMode(.inline)
        .usernamePickSheet(isPresented: $pickingUsername, store: LedgerStore(path: vaultPath)) { Task { await reload() } }
        .sheet(isPresented: $creatingGroup) {
            NavigationStack {
                let store = LedgerStore(path: vaultPath)
                CreateGroupSheet(store: store, needsYourName: (try? store.ensureSelf())?.name == nil) { _ in
                    creatingGroup = false
                    Task { await readFacts() }
                }
            }
        }
        .navigationDestination(item: $pushing) { place in stepDestination(place) }
        .task { await reload() }
        // D-221 (S7): runs on every appearance (and when a fact's source changes); no second read from onAppear.
        .task(id: factsKey) { await readFacts() }
        .onChange(of: scenePhase) { _, phase in
            // The notification permission and the phone's passcode change in the phone's Settings.
            if phase == .active { lockAvailable = AppLock.available }
            if phase == .active { Task { await readFacts() } }
        }
        .onChange(of: remote.signOuts) { Task { await reload() } }
    }

    /// D-221: what the facts depend on that the phone tells us about: the profile, sync, the two preferences.
    private var factsKey: String {
        "\(profileRevision)|\(SyncStatus.shared.revision)|\(currencyRaw ?? "")|\(lockRaw ?? "")|\(signedIn)|\(username ?? "")|\(Approval.shared.state)"
    }

    private func readFacts() async {
        let generation = factsReads.next()
        let known = username != nil || usernameState.map { $0 != UsernameState.none } ?? false
        let read = await SetupFacts.read(store: LedgerStore(path: vaultPath), account: Account.shared, hasUsername: known)
        if factsReads.isLatest(generation) { facts = read }
    }

    /// D-221 (L41): where each step's "Do it" goes. Every place, no default: a new one cannot be forgotten.
    private func go(_ place: StepPlace) {
        switch place {
        case .profile, .data, .security, .profileAccount: pushing = place
        case .createGroup: creatingGroup = true
        case .username: pickingUsername = true
        }
    }

    @ViewBuilder
    private func stepDestination(_ place: StepPlace) -> some View {
        switch place {
        case .profile: ProfileScreen(store: LedgerStore(path: vaultPath))
        case .data: SettingsSectionScreen(section: .data, vaultPath: vaultPath)
        case .security: SettingsSectionScreen(section: .security, vaultPath: vaultPath)
        case .profileAccount: SettingsSectionScreen(section: .profileAccount, vaultPath: vaultPath)
        case .createGroup, .username: EmptyView()
        }
    }

    @ViewBuilder
    private func destination(_ section: SettingsSection) -> some View {
        if section == .about {
            AboutScreen()
        } else {
            SettingsSectionScreen(section: section, vaultPath: vaultPath)
        }
    }

    /// D-222 (L56 to L58): the section's tile, title and live summary.
    private func row(_ section: SettingsSection) -> some View {
        SettingsRow(glyph: section.glyph, title: section.title, parts: YouRows.parts(section, rowFacts))
    }

    /// D-222 (L58): what the rows' summaries read, live: the lock's timeout follows its stored key as it changes.
    private var rowFacts: YouRowFacts {
        YouRowFacts(signedIn: signedIn, methodLabel: method?.rowLabel, phoneCount: phoneCount,
                    theme: AppTheme.parse(themeRaw), mode: AppearanceMode.parse(appearanceRaw),
                    lockTimeout: LockTimeout.parse(lockRaw), lockAvailable: lockAvailable,
                    version: AboutInfo.current().app, approval: Approval.shared.state)
    }

    private func reload() async {
        let store = LedgerStore(path: vaultPath)
        signedIn = Account.shared.isSignedIn
        method = Account.shared.method(store)
        lockAvailable = AppLock.available
        deletionDate = signedIn ? await Phones.pendingDeletion() : nil
        username = signedIn ? await People.myUsername() : nil
        // A failed read (nil) keeps what was known (UsernameState.merge).
        let read = signedIn ? await People.accountUsername() : nil
        usernameState = UsernameState.merge(known: usernameState, read: read, signedIn: Account.shared.isSignedIn)
        await readFacts()
        // Fix wave (S7): the phone count last, a server read that can take many seconds offline; the setup steps above
        // never wait for it (Android's twin reads it after the merge too).
        phoneCount = signedIn ? await Phones.activeCount() : nil
    }
}

/// D-206: one of You's groups, rendered from SettingsMap. The cards are the
/// ones the single Settings page had (D-126 to D-198), unchanged.
struct SettingsSectionScreen: View {
    let section: SettingsSection
    let vaultPath: String
    /// D-198: a sign-out while this page is open takes the account controls away.
    @ObservedObject private var remote = RemoteSignOut.shared

    /// D-225 fix wave: the account state on the first frame (the rows it decides are there from the start, never
    /// sliding in under a finger once `reload()` reads it again).
    @State private var model = accountHubModel(signedIn: Account.shared.isSignedIn, syncOn: true, approval: Approval.shared.state)
    @State private var busy = false
    /// D-172: how this phone signed in; nil for a sign-in from before D-172.
    @State private var method: SignInMethod?
    /// D-144: the same key `AppearanceApplier` watches at the root, so a
    /// change here repaints every window at once.
    @AppStorage(AppearanceMode.storageKey) private var appearanceRaw: String?
    @AppStorage(AppTheme.storageKey) private var themeRaw = AppTheme.aurora.rawValue
    @AppStorage(TextSize.storageKey) private var textStep = 0
    /// D-216 (Design 4): the App icon choice, read when the person leaves Appearance.
    @AppStorage(AppIconChoice.storageKey) private var iconRaw = AppIconChoice.match.rawValue
    /// D-222 (L64): the sample line's currency, the app's own default.
    @AppStorage(DefaultCurrency.storageKey) private var currencyRaw: String?
    /// D-225 (Design 4): App lock's row reads the stored timeout and whether this phone can lock (re-read on return: a
    /// passcode is set in the phone's Settings), as You's row does.
    @AppStorage(LockTimeout.storageKey) private var lockRaw: String?
    @State private var lockAvailable = AppLock.available
    @Environment(\.scenePhase) private var scenePhase
    /// D-225 (Design 4): Your phones' summary (the account's phones' names, this phone first), read once per visit when
    /// signed in; nil when unknown, so the row shows no summary.
    @State private var phonesLine: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // D-225 (Designs 4, 5): Security and Your data are list cards, drawn from the same tested map.
                switch section {
                case .security:
                    securityPage
                case .data:
                    yourDataPage
                default:
                    // Decision 252: drawn card by card (`SettingsMap.cards`): Profile and account's first card holds how you
                    // are signed in and Sign out together.
                    ForEach(SettingsMap.cards(SettingsMap.shown(section, model: model)), id: \.self) { items in
                        if items == SettingsMap.accountCard {
                            // The method row, 10 below it the sign-out line, 10 below that Sign out (the card's spacing;
                            // Android's twin).
                            card {
                                signedInLine
                                signOutLine
                            }
                        } else if let item = items.first {
                            view(for: item)
                        }
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground() // D-176
        .navigationTitle(section.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .onChange(of: remote.signOuts) { Task { await reload() } }
        // D-254: approval arriving (or taken away) while this page is open redraws the Sync card at once.
        .onChange(of: Approval.shared.state) { Task { await reload() } }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { lockAvailable = AppLock.available }
        }
        // D-225: and when a page pushed from here closes (a lock set while the App lock page was open; Android's twin).
        .onAppear { lockAvailable = AppLock.available }
        // D-216 (Design 4): leaving Appearance is when the icon changes (Appearance pushes no page, so this is the person
        // leaving it): iOS's one alert arrives as the answer to the choosing just done, at most once per visit.
        .onDisappear {
            if section == .appearance {
                AppIconSwitcher.applyIfNeeded(choice: AppIconChoice.parse(iconRaw), theme: AppTheme.parse(themeRaw))
            }
            // D-244 (ADR-0043): what was chosen here goes up to the account in one push (signed in, sync on).
            SettingsSync.pageClosed()
        }
    }

    /// D-225 (Design 4; L67 to L70; Q15 a): Security, top to bottom 16 apart: the jailbreak warning (only on such a phone),
    /// the safe card (App lock with its timeout, the screen-privacy switch, Your phones with their names when signed in),
    /// then "Careful" alone, 8 above the danger card (Erase this phone's ledger, which opens the Erase page, and Delete
    /// account when signed in). The items and their order are SettingsMap's, split by `SecurityRows.partition`. Android's
    /// twin is `SecurityPage` in AccountHubScreen.kt.
    @ViewBuilder
    private var securityPage: some View {
        let cards = SecurityRows.partition(SettingsMap.items(.security, signedIn: model.showsAccountControls))
        if DeviceIntegrity.looksJailbroken {
            ModifiedPhoneWarning()
        }
        ListCard {
            ForEach(cards.safe, id: \.self) { item in securityRow(item) }
        }
        if !cards.careful.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                sectionHeader(YouCopy.careful)
                ListCard {
                    ForEach(cards.careful, id: \.self) { item in securityRow(item) }
                }
            }
        }
    }

    @ViewBuilder
    private func securityRow(_ item: SettingItem) -> some View {
        switch item {
        case .appLock:
            // D-163: SPEC §3.7's auto-lock, on by default; its timeout, or that the phone cannot lock.
            NavigationLink { AppLockPage() } label: {
                ListRow(title: AppLockCopy.section,
                        parts: [SecurityRows.appLockLine(timeout: LockTimeout.parse(lockRaw), available: lockAvailable)],
                        trailing: .chevron)
            }
            .buttonStyle(.plain)
        case .privacy:
            // D-164: the rest of SPEC §3.7.
            PrivacyRow()
        case .phones:
            // D-198: SPEC §3.5's phone list; a failed read leaves the summary out.
            NavigationLink { PhonesScreen() } label: {
                ListRow(title: PhonesCopy.title, parts: phonesLine.map { [$0] } ?? [], trailing: .chevron)
            }
            .buttonStyle(.plain)
        case .erase:
            // D-168: the deliberate erase, set apart, never automatic: the row opens the page that says what goes, whose
            // button asks first.
            NavigationLink { EraseScreen() } label: {
                ListRow(title: EraseCopy.button, parts: [SecurityRows.eraseSummary], trailing: .chevron, tone: .danger)
            }
            .buttonStyle(.plain)
        case .deleteAccount:
            // D-198: SPEC §4.4's deletion.
            NavigationLink { DeleteAccountScreen() } label: {
                ListRow(title: PhonesCopy.deleteTitle, parts: [SecurityRows.deleteAccountSummary], trailing: .chevron, tone: .danger)
            }
            .buttonStyle(.plain)
        default:
            EmptyView()
        }
    }

    /// D-225 (Design 5; L74): Your data, one list card: Default currency (its code, opening the currency list page, whose
    /// "Common" section holds the old quick choices), Export (GUARANTEES #4: one tap) and Import, each explained on two
    /// lines; the currency caption 8 under the card. Android's twin is `YourDataPage` in AccountHubScreen.kt.
    private var yourDataPage: some View {
        VStack(alignment: .leading, spacing: 8) {
            ListCard {
                ForEach(SettingsMap.items(.data, signedIn: model.showsAccountControls), id: \.self) { item in dataRow(item) }
            }
            CardCaption(CurrencyCopy.detail)
        }
    }

    @ViewBuilder
    private func dataRow(_ item: SettingItem) -> some View {
        switch item {
        case .currency:
            // D-165: SPEC §5.2's currencies; the default is this phone's.
            NavigationLink {
                CurrencyListScreen(selection: Binding(get: { DefaultCurrency.current(stored: currencyRaw) }, set: { SettingsSync.set(DefaultCurrency.storageKey, $0) }),
                                   suggested: DefaultCurrency.suggestion(stored: currencyRaw))
            } label: {
                ListRow(title: CurrencyCopy.label, trailing: .value(DefaultCurrency.current(stored: currencyRaw), chevron: true))
            }
            .buttonStyle(.plain)
        case .export:
            // D-144: GUARANTEES #4, export at any time, in one tap.
            NavigationLink { ExportScreen(store: LedgerStore(path: vaultPath)) } label: {
                ListRow(title: YourDataCopy.export, parts: [YourDataCopy.exportDetail], summaryLines: 2, trailing: .chevron)
            }
            .buttonStyle(.plain)
        case .importData:
            // D-166: SPEC §9.5's importers.
            NavigationLink { ImportFlowScreen() } label: {
                // D-234 (decision 100): three lines, the words kept: two cut "added." on a 375 pt or 360 dp phone (T1).
                ListRow(title: ImportCopy.entry, parts: [ImportCopy.entryDetail], summaryLines: 3, trailing: .chevron)
            }
            .buttonStyle(.plain)
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func view(for item: SettingItem) -> some View {
        switch item {
        case .signIn:
            card {
                Text("No account")
                    .font(TypeRole.body())
                    .foregroundStyle(Color(Tokens.text))
                // A statement, not a pitch. GUARANTEES #5 forbids the nudge,
                // and "everything works" is the honest whole truth.
                Text(
                    "SKIPTI works fully without one. Your ledger, your "
                    + "balances and your exports are all on this device."
                )
                .font(TypeRole.body(TypeScale.detail))
                .foregroundStyle(Color(Tokens.textDim))
                NavigationLink("Sign in") {
                    SignInScreen()
                }
                // SECONDARY, never primary (D-128, GUARANTEES #5).
                .buttonStyle(SecondaryButtonStyle())
            }
        case .signedInAs:
            card { signedInLine }
        case .signOut:
            card { signOutLine }
        case .sync:
            syncCard
        case .notifications:
            // D-196: pushes come with the account and sync.
            NotificationsCard(syncOn: model.syncOn)
        case .findMe:
            // D-197: your username and whether people can find you.
            FindMeCard(store: LedgerStore(path: vaultPath))
        case .theme:
            // D-222 (L59): the theme row and the mode control sit on the ground; only Text size is a card.
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: AppearanceCopy.cards[0]).sectionLabel().foregroundStyle(Tokens.textDim)
                HStack(spacing: 8) {
                    ForEach(AppTheme.allCases) { theme in
                        ThemeCard(theme: theme, selected: AppTheme.parse(themeRaw) == theme) {
                            // D-250: the whole window crosses over to the new theme (the door decides).
                            ThemeCrossfade.change { SettingsSync.set(AppTheme.storageKey, theme.rawValue) }
                        }
                    }
                }
            }
        case .appearanceMode:
            let theme = AppTheme.parse(themeRaw)
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: AppearanceCopy.cards[1]).sectionLabel().foregroundStyle(Tokens.textDim)
                // D-212: a phone that stored `night` before reads as Dark (AppearanceMode.parse).
                // D-222 (L66, Q10 a): a line under the control only in Norse, 6 below it (Android's twin).
                VStack(alignment: .leading, spacing: 6) {
                    ModeControl(theme: theme, stored: Binding(
                        get: { AppearanceMode.parse(appearanceRaw) },
                        set: { mode in ThemeCrossfade.change { SettingsSync.set(AppearanceMode.storageKey, mode.rawValue) } }
                    ))
                    if theme.forcesDark {
                        Text(verbatim: AppearanceCopy.norseAlwaysDark)
                            .font(TypeRole.body(AppearanceCopy.detailSize))
                            .foregroundStyle(Tokens.textDim)
                    }
                }
            }
            // D-216 (Design 5): App icon, after Light or dark and before Text size (theme, mode and icon are the look), a
            // sibling in the page's stack, 16 below the mode control. Absent where the phone cannot change icons.
            if UIApplication.shared.supportsAlternateIcons {
                AppIconSection()
            }
        case .textSize:
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: AppearanceCopy.cards[2]).sectionLabel().foregroundStyle(Tokens.textDim)
                card {
                    // The slider row, 8, the sample, 6, the caption (Android's twin draws the same).
                    VStack(alignment: .leading, spacing: 0) {
                        TextSizeSlider(step: Binding(get: { textStep }, set: { SettingsSync.set(TextSize.storageKey, $0) }))
                        // D-222 (L64): the live sample, growing with the text size; its amount from core.
                        Separator.text(SampleLine.parts(amount: SampleLine.amount()), size: TypeScale.sample)
                            .font(TypeRole.name(TypeScale.sample)).foregroundStyle(Tokens.text)
                            .padding(.top, 8)
                            .accessibilityLabel(Text(verbatim: SampleLine.spoken(amount: SampleLine.amount())))
                        Text(verbatim: AppearanceCopy.textSizeCaption).font(TypeRole.body(AppearanceCopy.detailSize)).foregroundStyle(Tokens.textDim)
                            .padding(.top, 6)
                    }
                }
            }
            // D-224 (L48): the Haptics row, after the Text size card (a sibling in the page's stack, 16 below it).
            HapticsRow()
            // D-231 (decision 69): the Less motion row, after Haptics (a sibling in the page's stack, 16 below it).
            MotionRow()
            // D-249 (decision 163): Spending on Home, after Less motion; this phone's own (decision 189).
            SpendingHomeRow()
        case .appLock, .privacy, .phones, .erase, .deleteAccount, .currency, .export, .importData:
            // D-225: Security's and Your data's items are drawn by securityPage and yourDataPage.
            EmptyView()
        case .aboutPage, .deletionPending:
            // About opens AboutScreen from the hub; the deletion notice sits on the hub.
            EmptyView()
        }
    }

    /// A section's label alone (D-225, Design 2, L70: the hairline under it went), 8 above its card.
    private func sectionHeader(_ title: String) -> some View {
        Text(verbatim: title)
            .sectionLabel()
            .foregroundStyle(Color(Tokens.textDim))
            .accessibilityAddTraits(.isHeader)
            .accessibilityLabel(title)
    }

    /// ADR-0034 §3 / GUARANTEES #5 as amended (D-125): sync is on once an
    /// account exists and off in one tap. The copy refuses to overclaim.
    /// D-254: while the account waits for approval, or was not approved, the card says so and has no switch.
    @ViewBuilder
    private var syncCard: some View {
        switch model.state {
        case .waiting:
            approvalSyncCard(value: ApprovalCopy.syncWaitingValue, body: ApprovalCopy.syncWaitingBody, valueColor: Tokens.accent)
        case .notApproved:
            approvalSyncCard(value: ApprovalCopy.syncDeniedValue, body: ApprovalCopy.syncDeniedBody, valueColor: Tokens.textDim)
        case .noAccount, .active:
            switchSyncCard
        }
    }

    /// D-254: the Sync card while the account is not approved: the value and the words, no switch.
    private func approvalSyncCard(value: String, body: String, valueColor: Color) -> some View {
        card {
            HStack {
                Text("Sync")
                    .sectionLabel()
                    .foregroundStyle(Color(Tokens.textDim))
                Spacer()
                Text(verbatim: value)
                    .microLabelFont()
                    .foregroundStyle(valueColor)
            }
            Text(verbatim: body)
                .font(TypeRole.body(TypeScale.detail))
                .foregroundStyle(Color(Tokens.text))
        }
    }

    @ViewBuilder
    private var switchSyncCard: some View {
        card {
            HStack {
                Text("Sync")
                    .sectionLabel()
                    .foregroundStyle(Color(Tokens.textDim))
                Spacer()
                Text(model.syncOn ? "On" : "Off")
                    .microLabelFont()
                    .foregroundStyle(Color(model.syncOn ? Tokens.owing : Tokens.textDim))
            }
            Text(
                model.syncOn
                ? "Your expenses sync to your devices as you add them."
                : "Nothing is leaving this device. Your ledger still works "
                    + "exactly as it does now."
            )
            .font(TypeRole.body(TypeScale.detail))
            .foregroundStyle(Color(Tokens.text))
            Button(model.syncOn ? "Turn sync off" : "Turn sync on") {
                Task { await toggleSync() }
            }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(busy)
            if model.syncOn {
                Text(
                    "Turning sync off stops this device sending. It does not "
                    + "delete what your account has already synced."
                )
                .font(TypeRole.body(TypeScale.detail))
                .foregroundStyle(Color(Tokens.textDim))
            }
        }
    }

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        // D-176: frosted glass over the Aurora glow, 24-point corners.
        VStack(alignment: .leading, spacing: 10) { content() }
            .glassCard()
    }

    /// How this phone signed in (D-172), or "Signed in" for a sign-in from before it.
    @ViewBuilder
    private var signedInLine: some View {
        if let method {
            methodRow(method)
        } else {
            Text("Signed in")
                .font(TypeRole.body())
                .foregroundStyle(Color(Tokens.text))
        }
    }

    /// D-155: a plain sign-out, its line above the button.
    @ViewBuilder
    private var signOutLine: some View {
        Text(AccountSignOut.note)
            .font(TypeRole.body(TypeScale.detail))
            .foregroundStyle(Color(Tokens.textDim))
        Button("Sign out") {
            Task { await signOut() }
        }
        .buttonStyle(SecondaryButtonStyle())
        .disabled(busy)
    }

    /// D-172: Apple's logo, Google's G, or the address, with the words.
    private func methodRow(_ method: SignInMethod) -> some View {
        HStack(spacing: 10) {
            SignInMethodMark(method: method)
            Text(verbatim: method.label)
                .font(TypeRole.body())
                .foregroundStyle(Color(Tokens.text))
        }
        .accessibilityElement(children: .combine)
    }

    private func reload() async {
        let store = LedgerStore(path: vaultPath)
        method = Account.shared.method(store)
        model = accountHubModel(signedIn: Account.shared.isSignedIn, syncOn: CloudSync.syncEnabled(store),
                                approval: Approval.shared.state)
        lockAvailable = AppLock.available
        // D-225 (Design 4): last, a server read that can take many seconds offline.
        phonesLine = section == .security && model.state == .active ? await Phones.summary() : nil
    }

    private func signOut() async {
        busy = true
        defer { busy = false }
        // D-196: the push address leaves the cloud while the account may
        // still write it.
        await Push.shared.stop(inCloud: true)
        Account.shared.signOut()
        // D-224 (Design 7).
        DoneCenter.shared.post(DoneMoments.line(DoneCopy.signedOut))
        await reload()
    }

    private func toggleSync() async {
        busy = true
        defer { busy = false }
        try? CloudSync.setSyncEnabled(LedgerStore(path: vaultPath), !model.syncOn)
        CloudSync.shared.syncSwitched()
        await reload()
    }
}
