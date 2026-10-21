import SwiftUI
import UIKit

// D-225 (S2, the founder's settings screens and form twins): the one list every settings page, form and choice page
// draws, so the two phones draw the same rows (Designs 1, 2, 3 and 10). Android's twins are in ListRows.kt.

/// What a list row ends with.
enum RowTrailing {
    /// S1's chevron in `textDim`: the row opens a page (the caller makes the whole row a `NavigationLink` or `Button`).
    case chevron
    /// A value at 16 in `textDim` on one line, then the chevron when the row can be tapped ("Settles in  INR").
    case value(String, chevron: Bool)
    /// The drawn switch (`ThemeSwitch`); the whole row toggles it, and VoiceOver reads the row as one real switch.
    case toggle(Binding<Bool>, enabled: Bool = true)
    /// Lucide's check in `accent` when chosen, nothing otherwise (a choice page's rows).
    case check(Bool)
    /// A drawn value (Group settings' picture: the group tile at 30), then the chevron when the row can be tapped.
    /// Decorative: the row speaks for it (Android's `RowTrailing.Piece`).
    case piece(AnyView, chevron: Bool)
    /// D-234 (A2): a header that opens rows under it: the value as `.value` draws it, then the chevron turned 90 degrees
    /// (pointing down) while open. VoiceOver reads the value and "expanded" or "collapsed" (`RowDisclosure`), as a
    /// button. Android's `RowTrailing.Disclosure`.
    case disclosure(String, expanded: Bool)
    case none
}

/// D-234 (A2): a disclosure row's words and turn, the same on both phones (Android's `RowDisclosure` in ListRows.kt).
enum RowDisclosure {
    static let expanded = "expanded"
    static let collapsed = "collapsed"

    /// What VoiceOver reads after the title: the value, then the state.
    static func spokenValue(_ value: String, expanded open: Bool) -> String {
        value + ", " + (open ? RowDisclosure.expanded : RowDisclosure.collapsed)
    }

    /// The chevron's turn in degrees: pointing right while closed, down while open.
    static func chevronDegrees(expanded open: Bool) -> Double { open ? 90 : 0 }
}

/// A row's title colour: `text`, or `owed` for a row that destroys something (art.py 41: 4.5 on every card ground).
enum RowTone: Equatable {
    case normal, danger
}

/// D-225 (Design 1): S1's list card: the rows on `glassCard(padding: 0)`, padded `listPadH` by `listPadV`, a 1 pt
/// `hairline` between two rows and none before the first or after the last. Rows from a `ForEach` count one by one and
/// a false `if` adds none.
struct ListCard<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        _VariadicView.Tree(ListCardRows()) { content }
            .padding(.horizontal, ThemeArt.m("listPadH"))
            .padding(.vertical, ThemeArt.m("listPadV"))
            .glassCard(padding: 0)
    }
}

/// The card's rows, one by one, with the hairline drawn before every row but the first.
private struct ListCardRows: _VariadicView_MultiViewRoot {
    func body(children: _VariadicView.Children) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(children) { row in
                if row.id != children.first?.id {
                    Rectangle().fill(Tokens.hairline).frame(height: 1)
                }
                row
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// D-225 (Design 1): one row of a list: the optional tile (`SettingsTile`), a `rowGap`, the title through the name role
/// at 16 over the summary parts joined by the theme's separator (Norse's gold ᛫) at `TypeScale.detail` in `textDim`,
/// `rowLineGap` under the title (`summaryLines` 1 ends in an ellipsis, 0 takes what it needs), then the trailing piece.
/// Padded `rowPadV`, never shorter than `touchTarget`, so a one-line row is 48 on both phones. One element to VoiceOver,
/// read "title, part, part" (the separator never read), with the trailing piece's role. D-216 (Design 5): `leading`, a
/// drawn view where the tile goes (App icon's previews), Android's `leading` slot; decorative, as the tile is.
struct ListRow: View {
    var glyph: LucideGlyph? = nil
    var leading: AnyView? = nil
    let title: String
    var parts: [String] = []
    var summaryLines = 1
    var trailing: RowTrailing = .none
    var tone: RowTone = .normal

    private var spoken: String { YouRows.spoken(title: title, parts: parts) }

    var body: some View {
        switch trailing {
        case .toggle(let isOn, let enabled):
            line
                .onTapGesture { if enabled { isOn.wrappedValue.toggle() } }
                .accessibilityRepresentation {
                    Toggle(isOn: isOn) { Text(verbatim: spoken) }
                        .disabled(!enabled)
                }
        case .value(let value, _):
            line
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: spoken))
                .accessibilityValue(Text(verbatim: value))
        case .disclosure(let value, let expanded):
            line
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: spoken))
                .accessibilityValue(Text(verbatim: RowDisclosure.spokenValue(value, expanded: expanded)))
                .accessibilityAddTraits(.isButton)
        case .chevron, .check, .piece, .none:
            line
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: spoken))
        }
    }

    private var line: some View {
        HStack(spacing: ThemeArt.m("rowGap")) {
            if let glyph { SettingsTile(glyph: glyph) }
            if let leading { leading }
            VStack(alignment: .leading, spacing: ThemeArt.m("rowLineGap")) {
                Text(verbatim: title).font(TypeRole.name(16)).foregroundStyle(tone == .danger ? Tokens.owed : Tokens.text)
                if !parts.isEmpty {
                    Separator.text(parts, size: TypeScale.detail)
                        .font(TypeRole.body(TypeScale.detail))
                        .foregroundStyle(Tokens.textDim)
                        .lineLimit(summaryLines == 0 ? nil : summaryLines)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            end
        }
        .padding(.vertical, ThemeArt.m("rowPadV"))
        .frame(minHeight: ThemeArt.m("touchTarget"))
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var end: some View {
        switch trailing {
        case .chevron:
            RowChevron(color: Tokens.textDim)
        case .value(let value, let chevron):
            // The chevron drawing's own side bearing is the gap between the value and it. A value shares the row with
            // the words (half each), sits at the end and ellipsises, so a long value (a group's name) never pushes the
            // title out of the row (Android's twin, Task 5).
            HStack(spacing: 0) {
                Text(verbatim: value).font(TypeRole.body(16)).foregroundStyle(Tokens.textDim).lineLimit(1)
                if chevron { RowChevron(color: Tokens.textDim) }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        case .disclosure(let value, let expanded):
            // D-234 (A2): `.value`'s layout; the chevron turns in the caller's transaction (a snap under the motion door).
            HStack(spacing: 0) {
                Text(verbatim: value).font(TypeRole.body(16)).foregroundStyle(Tokens.textDim).lineLimit(1)
                RowChevron(color: Tokens.textDim)
                    .rotationEffect(.degrees(RowDisclosure.chevronDegrees(expanded: expanded)))
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        case .toggle(let isOn, let enabled):
            ThemeSwitch(isOn: isOn.wrappedValue, enabled: enabled)
        case .piece(let piece, let chevron):
            HStack(spacing: 0) {
                piece.accessibilityHidden(true)
                if chevron { RowChevron(color: Tokens.textDim) }
            }
        case .check(let chosen):
            // C2's check at the currency list's 20.
            if chosen {
                LucideIcon(glyph: .check, size: 20).foregroundStyle(Tokens.accent)
            }
        case .none:
            EmptyView()
        }
    }
}

/// D-225 (Design 3, L-D217-9): the switch, drawn the same on both phones in iOS's proportions: a `switchWidth` by
/// `switchHeight` track in the theme's pill shape (capsule, Norse notched, Vardr Labs square), the `switchThumb` thumb in
/// the person tile's shape inscribed in its box (circle, diamond, square) with the segment's shadow, at
/// `SwitchGeometry.thumbCentreX`. Paint from `SwitchPaint` (ON an accent track and a surface thumb; OFF a ringTrack track
/// inside a `switchBorder` border in textFaint and a textDim thumb). Disabled, the whole switch at
/// `ButtonLook.disabledAlpha`. The thumb slides in `switchTravel` only when D-214's still rule lets art move. Decorative:
/// the row that holds it is the switch to VoiceOver.
struct ThemeSwitch: View {
    let isOn: Bool
    var enabled = true

    @Environment(\.motionReduced) private var reduceMotion

    var body: some View {
        let theme = AppearanceState.shared.theme
        let paint = SwitchPaint.of(on: isOn)
        let track = ThemeArt.pillShape(theme)
        let height = ThemeArt.m("switchHeight")
        let thumb = ThemeArt.m("switchThumb")
        let border = ThemeArt.m("switchBorder")
        let knob = TileClip.shape(TileShape.of(theme, person: true), size: thumb)
        let moves = MotionClock.momentTicks(reduceMotion: reduceMotion, active: true)
        ZStack(alignment: .topLeading) {
            track.fill(Self.ink(paint.track))
            if let edge = paint.border {
                track.stroke(Self.ink(edge), lineWidth: border).padding(border / 2)
            }
            SwitchThumb(x: SwitchGeometry.thumbCentreX(on: isOn) - thumb / 2, y: (height - thumb) / 2, side: thumb,
                        knob: knob, paint: Self.ink(paint.thumb))
                .shadow(color: .black.opacity(ThemeArt.m("segmentShadowAlpha")),
                        radius: ThemeArt.m("segmentShadowRadius"), y: ThemeArt.m("segmentShadowY"))
                // D-225 follow-up (re-look R1): the thumb slides in `switchTravel` on the flip itself; nil under D-214's
                // still rule (a jump).
                .animation(moves ? .easeInOut(duration: ThemeArt.m("switchTravel")) : nil, value: isOn)
        }
        .frame(width: ThemeArt.m("switchWidth"), height: height)
        .compositingGroup()
        .opacity(enabled ? 1 : ButtonLook.disabledAlpha)
        .accessibilityHidden(true)
    }

    /// A token by its name (`SwitchPaint` names them), resolved per theme and mode.
    static func ink(_ token: String) -> Color { Tokens.dynamic(Tokens.palette[token]!) }
}

/// D-225 follow-up (re-look R1): the switch's thumb at its place. The place is the one value the slide interpolates
/// (`animatableData`), so the flip moves the thumb in the same update that snaps its paint: the fill takes no animation
/// (its colour jumps, as Android's does). Measured on screen (ThemeArtTests): the scoped body form never animated a
/// flip; a transaction override above the place stopped the slide too; a place that followed the flip one update later
/// left the new paint standing at the old place for 140 to 170 ms on the simulator.
private struct SwitchThumb: View, Animatable {
    var x: CGFloat
    let y: CGFloat
    let side: CGFloat
    let knob: AnyShape
    let paint: Color

    var animatableData: CGFloat {
        get { x }
        set { x = newValue }
    }

    var body: some View {
        knob.fill(paint)
            .frame(width: side, height: side)
            .transaction { $0.animation = nil }
            .offset(x: x, y: y)
    }
}

/// D-225 (Design 2): the explanation under a card (what a `Form` footer used to carry): `TypeScale.detail` in `textDim`
/// on the page ground (art.py 41: 4.5 on every ground's worst pixel), flush with the card's edge as a section label is
/// (Android's twin pads none either). The caller sets it 8 under its card.
struct CardCaption: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(verbatim: text)
            .font(TypeRole.body(TypeScale.detail))
            .foregroundStyle(Tokens.textDim)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// D-225 (Design 1, item 16): a text field as a list row: the field padded `rowPadV`, never shorter than `touchTarget`,
/// its placeholder in `textFaint`, its words in `text` in the font the caller asks for (the share inputs' numeric role),
/// then an optional trailing piece (the clear cross). D-225 T2 review: a tap anywhere in the field's part of the row
/// (its padding and the 48 minimum included) focuses the field, as Android's twin does; the trailing piece keeps its
/// own taps. D-225 T6: `spoken` names the field to VoiceOver apart from its placeholder (New group's "Person 2"), as
/// Android's `FieldRow(spoken:)`; by default the placeholder names it.
/// D-248 (U29.2): a field row whose focus its form drives: the row's id among the form's rows, the form's focus, and
/// what Return does (the keyboard's key reads "Next").
struct FieldFocus {
    let id: Int
    let focus: FocusState<Int?>.Binding
    let onReturn: () -> Void
}

/// D-253 (the store look, shot 4): what a field row shows. A LABELLED row (a split kind's person row: Exact, Percent,
/// Shares, Adjustment) keeps its label, the person's name, at its start whatever is typed, and its field trails, so
/// "1500" never stands alone with the placeholder gone; its prompt is the placeholder's first words ("Amount" of
/// "Amount for Meera"), and VoiceOver still hears the whole placeholder. A row with no label is today's. Android's twin
/// is `FieldRowShows` in ListRows.kt.
enum FieldRowShows {
    /// The label drawn before the field: the row's label, typed into or not.
    static func label(_ label: String?, text: String) -> String? { label }

    /// The prompt in the field: the placeholder, or for a labelled row the words before " for ".
    static func prompt(_ placeholder: String, label: String?) -> String {
        guard label != nil, let cut = placeholder.range(of: " for ") else { return placeholder }
        return String(placeholder[..<cut.lowerBound])
    }
}

struct FieldRow<Trailing: View>: View {
    let placeholder: String
    /// D-253: the person's name before the field (`FieldRowShows`); nil for an unlabelled row.
    var label: String? = nil
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var font: Font = TypeRole.body()
    var spoken: String? = nil
    var form: FieldFocus? = nil
    let trailing: Trailing
    @FocusState private var focused: Bool

    init(_ placeholder: String, label: String? = nil, text: Binding<String>, keyboard: UIKeyboardType = .default,
         font: Font = TypeRole.body(), spoken: String? = nil, form: FieldFocus? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.placeholder = placeholder
        self.label = label
        _text = text
        self.keyboard = keyboard
        self.font = font
        self.spoken = spoken
        self.form = form
        self.trailing = trailing()
    }

    /// D-253: a labelled row's field sits at the row's end, its words flush with it.
    private var fieldAlignment: TextAlignment { label == nil ? .leading : .trailing }

    var body: some View {
        HStack(spacing: ThemeArt.m("rowGap")) {
            // D-253: the person's name, as a list row's title (`TypeRole.name(16)`), sharing the row half and half with
            // the field (a value row's layout); VoiceOver hears the field's whole name instead.
            if let shown = FieldRowShows.label(label, text: text) {
                Text(verbatim: shown).font(TypeRole.name(16)).foregroundStyle(Tokens.text).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityHidden(true)
            }
            TextField(text: $text, prompt: Text(verbatim: FieldRowShows.prompt(placeholder, label: label)).foregroundStyle(Tokens.textFaint)) {
                Text(verbatim: placeholder)
            }
            .multilineTextAlignment(fieldAlignment)
            .font(font)
            .foregroundStyle(Tokens.text)
            .tint(Tokens.accent)
            .keyboardType(keyboard)
            .accessibilityLabel(Text(verbatim: spoken ?? placeholder))
            .modifier(FieldFocusing(local: $focused, form: form))
            .padding(.vertical, ThemeArt.m("rowPadV"))
            .frame(maxWidth: .infinity, minHeight: ThemeArt.m("touchTarget"), alignment: .leading)
            .contentShape(Rectangle())
            .simultaneousGesture(TapGesture().onEnded {
                if let form { form.focus.wrappedValue = form.id } else { focused = true }
            })
            trailing
        }
        .frame(minHeight: ThemeArt.m("touchTarget"))
    }
}

extension FieldRow where Trailing == EmptyView {
    init(_ placeholder: String, label: String? = nil, text: Binding<String>, keyboard: UIKeyboardType = .default,
         font: Font = TypeRole.body(), spoken: String? = nil, form: FieldFocus? = nil) {
        self.init(placeholder, label: label, text: text, keyboard: keyboard, font: font, spoken: spoken, form: form) { EmptyView() }
    }
}

/// The row's own focus, or (D-248) its form's, with Return as the form says.
private struct FieldFocusing: ViewModifier {
    let local: FocusState<Bool>.Binding
    let form: FieldFocus?

    func body(content: Content) -> some View {
        if let form {
            content
                .focused(form.focus, equals: form.id)
                .submitLabel(.next)
                .onSubmit(form.onReturn)
        } else {
            content.focused(local)
        }
    }
}

/// One option of a choice page: what is stored, the row's title, and an optional summary.
struct ChoiceOption<Value: Hashable>: Identifiable {
    let value: Value
    let label: String
    var parts: [String] = []

    var id: Value { value }
}

/// D-225 (Design 10, Q15 a): every choice a form or a settings page makes, as a pushed page (Apple's own pattern for a
/// form's choices, Calendar's Repeat): one list card of `.check` rows, the chosen one checked and selected to VoiceOver,
/// and an optional caption under the card. A pick writes through `onPick` and returns, one tap; an option that waits on
/// a confirmation (App lock's Off) stays, and the caller returns once it is confirmed. No options draws the caption only.
struct ChoicePage<Option: Hashable>: View {
    let title: String
    let options: [ChoiceOption<Option>]
    let chosen: Option?
    var caption: String? = nil
    /// The options that wait on a confirmation before the page returns.
    var stays: (Option) -> Bool = { _ in false }
    let onPick: (Option) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if !options.isEmpty {
                    ListCard {
                        ForEach(options) { option in
                            Button {
                                onPick(option.value)
                                if !stays(option.value) { dismiss() }
                            } label: {
                                ListRow(title: option.label, parts: option.parts, trailing: .check(option.value == chosen))
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(option.value == chosen ? .isSelected : [])
                        }
                    }
                }
                if let caption { CardCaption(caption) }
            }
            // The currency list's place on the page (C2), which Android's PushedWindow draws too: 16 a side, the first
            // card where the grouped list put it.
            .padding(.horizontal, 16)
            .padding(.top, CurrencyListScreen.topPadding)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(Text(verbatim: title))
        .navigationBarTitleDisplayMode(.inline)
    }
}
