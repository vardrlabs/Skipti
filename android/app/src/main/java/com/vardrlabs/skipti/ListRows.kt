package com.vardrlabs.skipti

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.accessibilityClassName
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

/*
 * D-225 (S2, Designs 1, 2, 3, 9, 10; L67, L73, L74, L-D217-1, L-D217-9): the pieces every settings page and form draws,
 * so both phones draw ONE row: the list card, the list row with its trailing piece, the drawn theme switch, the field
 * row, the hairline field, the choice page, the caption under a card, and C2's pushed window that the currency list and
 * the choice page share. The twins of iOS's ListRows.swift (ThemeArtTest, AppearanceTest and YouTest read this file).
 */

/** What a [ListRow] ends in (Design 1). */
sealed interface RowTrailing {
    /** S1's chevron in `textDim`: the whole row is a button. */
    data object Chevron : RowTrailing

    /** A value at 16 in `textDim`, one line, then the chevron when the row opens something ("Settles in  INR"). */
    data class Value(val text: String, val chevron: Boolean) : RowTrailing

    /** The drawn [ThemeSwitch]; the whole row toggles it (Design 3). */
    data class Toggle(val isOn: Boolean, val onChange: (Boolean) -> Unit) : RowTrailing

    /** C2's Lucide check in `accent` when chosen, nothing otherwise (a choice page's option). [acts]: a tap on the
     *  chosen row still changes something (D-216's App icon rows: it turns Match theme off), so TalkBack must still be
     *  able to click it ([ListRows.checkRole]). */
    data class Check(val chosen: Boolean, val acts: Boolean = false) : RowTrailing

    /** D-225 T5 (Design 7): a drawn value that is not text (Group settings' picture: the group tile at 30), then the
     *  chevron when the row opens something. */
    class Piece(val chevron: Boolean, val content: @Composable () -> Unit) : RowTrailing

    /** D-234 (A2): a header that opens rows under it: the value as [Value] draws it, then the chevron turned 90 degrees
     *  (pointing down) while open; TalkBack reads the value, the state ([RowDisclosure]) and offers expand or collapse.
     *  The twin of iOS's `.disclosure`. */
    data class Disclosure(val text: String, val expanded: Boolean) : RowTrailing

    data object None : RowTrailing
}

/** D-234 (A2): a disclosure row's words and turn, the same on both phones (iOS's `RowDisclosure` in ListRows.swift). */
object RowDisclosure {
    const val EXPANDED = "expanded"
    const val COLLAPSED = "collapsed"

    /** What TalkBack reads as the row's state. */
    fun state(expanded: Boolean): String = if (expanded) EXPANDED else COLLAPSED

    /** The chevron's turn in degrees: pointing right while closed, down while open. */
    fun chevronDegrees(expanded: Boolean): Float = if (expanded) 90f else 0f
}

/** A row's title colour: `text`, or `owed` for a careful action (art.py 41: 4.5 on every card ground in every set). */
enum class RowTone { NORMAL, DANGER }

/** The row's pure decisions. */
object ListRows {
    /** What TalkBack reads for a row: "title, part, part", then a value (the separator is never read aloud). A switch's
     *  state and a check's selection come from the row's own role. The same line You's rows read ([YouRows.spoken]). */
    fun spoken(title: String, parts: List<String>, trailing: RowTrailing): String =
        YouRows.spoken(title, parts + listOfNotNull((trailing as? RowTrailing.Value)?.text, (trailing as? RowTrailing.Disclosure)?.text))

    /** D-225 fix wave (accessibility, the look's dump): the class a row reports, where its role does not set one. A
     *  switch row is an `android.widget.Switch` (Compose gives Role.Switch only a role description); a check row's
     *  RadioButton role already sets its class; any other row keeps Compose's own. */
    fun className(trailing: RowTrailing): String? = when {
        trailing is RowTrailing.Toggle -> "android.widget.Switch"
        trailing is RowTrailing.Check && checkRole(trailing) == null -> "android.widget.RadioButton"
        else -> null
    }

    /** D-216 (the look's F3): a check row's role. Compose reports a SELECTED node whose role is RadioButton (or Tab) as
     *  not clickable and offers TalkBack no click on it (Compose UI 1.12's accessibility delegate), which is right for a
     *  choice page, where tapping the chosen option does nothing. A chosen row whose tap still acts ([RowTrailing.Check.acts])
     *  drops the role instead: it keeps its selection (TalkBack says "Selected") and its click, and [className] keeps
     *  the RadioButton class. */
    fun checkRole(trailing: RowTrailing.Check): Role? = if (trailing.chosen && trailing.acts) null else Role.RadioButton

    /** D-225 fix wave (F3, M8): a drawn piece's slot (Group settings' picture), the same on both phones. */
    const val PIECE_SLOT = 30

    /** D-225 follow-up (R3, the re-look's prof pair): dp a text field's line sits in from its box at the top and the
     *  bottom, as iOS's text field draws it (1 pt): [HairlineField] pads it inside iOS's 10. */
    const val FIELD_INSET = 1f

    /** The tile size that keeps a [shape]'s DRAWN side inside the [slot] (Norse's diamond is drawn at size x sqrt 2,
     *  [tileDrawnSide]), so the tile sits in its slot and keeps Aurora's gap to the chevron. Every other shape is the
     *  slot. */
    fun pieceTile(shape: TileShape, slot: Int = PIECE_SLOT): Int =
        kotlin.math.floor(slot * slot / tileDrawnSide(shape, slot)).toInt()
}

/** Collects a [ListCard]'s rows, so the card can draw a hairline between them. */
class ListCardScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** D-225 (Design 1): S1's list card: the glass card at padding 0, its rows at the list padding, a hairline between rows
 *  and none after the last. [modifier] carries a group's semantics (a choice page's `selectableGroup()`). The twin of
 *  iOS's `ListCard`. */
// D-250: [moves], a form card whose rows come and go as it is filled (Add expense's: iOS's `.rowsMotion(formShape)`):
// its height eases over `ChangeMotion.CHANGE` instead of jumping ([movingSize]); a cut when motion is reduced.
@Composable
fun ListCard(dark: Boolean, modifier: Modifier = Modifier, moves: Boolean = false, build: ListCardScope.() -> Unit) {
    val rows = ListCardScope().apply(build).rows
    ListCardColumn(dark, modifier, moves) {
        rows.forEachIndexed { i, row ->
            if (i > 0) ListCardHairline(dark)
            row()
        }
    }
}

/** D-250: [ListCard]'s card around rows the caller draws itself (New group's people, which come and go through
 *  [MovingRows]), each separated by [ListCardHairline]. */
@Composable
fun ListCardColumn(dark: Boolean, modifier: Modifier = Modifier, moves: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val sizing = if (moves) Modifier.movingSize() else Modifier
    Column(modifier.glassCard(dark, padding = 0).then(sizing).padding(horizontal = ThemeArt.m("listPadH").dp, vertical = ThemeArt.m("listPadV").dp), content = content)
}

/** The hairline between two rows of a list card. */
@Composable
fun ListCardHairline(dark: Boolean) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hairline(dark)))
}

/** D-225 (Design 1): the one list row. An optional [leading] piece (You's and Haptics' tile), `rowGap` 13, the title in
 *  the name role at 16 (`owed` under [RowTone.DANGER]) over the summary ([parts] joined by the theme's separator, at the
 *  detail size in `textDim`, `rowLineGap` 2 under the title, [summaryLines] 1 with an ellipsis, 0 for unlimited), then
 *  the [trailing] piece. Padded `rowPadV` 10 at the 48 dp `touchTarget` minimum, so a one-line row is 48 on both phones.
 *  ONE node to TalkBack: a button (chevron, or [onClick] given), a Switch with its state (toggle), a RadioButton with
 *  its selection (check), speaking [ListRows.spoken], or [spoken] when the row's words alone would not say what it is
 *  (D-216: the App icon rows read "Aurora icon"). The twin of iOS's `ListRow`. */
@Composable
fun ListRow(
    title: String,
    dark: Boolean,
    parts: List<String> = emptyList(),
    trailing: RowTrailing = RowTrailing.None,
    tone: RowTone = RowTone.NORMAL,
    summaryLines: Int = 1,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    spoken: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val action = when (trailing) {
        is RowTrailing.Toggle -> Modifier.toggleable(
            value = trailing.isOn,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = trailing.onChange,
        )
        is RowTrailing.Check -> Modifier.selectable(
            selected = trailing.chosen,
            enabled = enabled,
            role = ListRows.checkRole(trailing),
            onClick = { onClick?.invoke() },
        )
        else -> if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ThemeArt.m("touchTarget").dp)
            .then(action)
            .padding(vertical = ThemeArt.m("rowPadV").dp)
            // D-225 fix wave (the look's dump): ONE node, the action's role and state with the row's words. Clearing the
            // row's children leaves the role nothing to split off into a second node (Compose added a stateless
            // RadioButton beside each choice row), and a switch row reports the Switch class.
            .clearAndSetSemantics {
                contentDescription = ListRows.spoken(title, parts, trailing)
                spoken?.let { contentDescription = it }
                ListRows.className(trailing)?.let { accessibilityClassName = it }
                // D-234 (A2): a header that opens rows says whether it is open, and offers the other.
                (trailing as? RowTrailing.Disclosure)?.let { d ->
                    stateDescription = RowDisclosure.state(d.expanded)
                    if (onClick != null) {
                        if (d.expanded) collapse { onClick(); true } else expand { onClick(); true }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(ThemeArt.m("rowGap").dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ThemeArt.m("rowLineGap").dp)) {
            Text(title, style = Type.name(dark, 16).copy(color = if (tone == RowTone.DANGER) Tok.owed(dark) else Tok.text(dark)), modifier = Modifier.clearAndSetSemantics {})
            if (parts.isNotEmpty()) {
                Text(
                    Separator.annotated(parts),
                    style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
                    maxLines = if (summaryLines == 0) Int.MAX_VALUE else summaryLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        if (trailing != RowTrailing.None) {
            Spacer(Modifier.width(ThemeArt.m("rowGap").dp))
            // A value shares the row with the title (half each), sits at the end and ellipsises: a long value (a group's
            // name) never pushes the title out of the row.
            RowEnd(trailing, dark, enabled, if (trailing is RowTrailing.Value || trailing is RowTrailing.Disclosure) Modifier.weight(1f) else Modifier)
        }
    }
}

/** The trailing piece a [ListRow] draws; decorative (the row speaks for it). */
@Composable
private fun RowEnd(trailing: RowTrailing, dark: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    when (trailing) {
        RowTrailing.None -> Unit
        RowTrailing.Chevron -> RowChevron(Tok.textDim(dark))
        is RowTrailing.Value -> Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(
                trailing.text,
                style = Type.bodyDim(dark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
            )
            if (trailing.chevron) RowChevron(Tok.textDim(dark))
        }
        is RowTrailing.Piece -> Row(verticalAlignment = Alignment.CenterVertically) {
            // D-225 fix wave (F3): the piece is laid out in its slot, centred; its drawn shape stays inside it.
            Box(Modifier.size(ListRows.PIECE_SLOT.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { trailing.content() }
            if (trailing.chevron) RowChevron(Tok.textDim(dark))
        }
        is RowTrailing.Disclosure -> Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(
                trailing.text,
                style = Type.bodyDim(dark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
            )
            // D-234 (A2): the chevron turns over the switch's travel; it snaps under the motion door.
            val motion = rememberMotionState()
            val still = motion.reduce
            val turn by animateFloatAsState(
                RowDisclosure.chevronDegrees(trailing.expanded),
                if (still) snap() else tween((ThemeArt.m("switchTravel") * 1000).roundToInt(), easing = EaseInOut),
                label = "disclosure",
            )
            Box(Modifier.rotate(turn)) { RowChevron(Tok.textDim(dark)) }
        }
        is RowTrailing.Toggle -> ThemeSwitch(trailing.isOn, dark, enabled)
        // C2's check, at the currency list's 20 dp.
        is RowTrailing.Check -> if (trailing.chosen) {
            Icon(TabIcons.Check, contentDescription = null, tint = Tok.accent(dark), modifier = Modifier.size(20.dp))
        }
    }
}

/** D-225 (Design 3; L-D217-9; art.py 42): the drawn switch, iOS's proportions on both phones: the `switchWidth` x
 *  `switchHeight` track in the theme's pill shape (capsule, Norse notched, Vardr Labs square), the `switchThumb` thumb
 *  `switchInset` inside it in the theme's person tile shape inscribed in its box (circle, diamond, square: S1's slider
 *  thumb rule) with S1's segment shadow; [SwitchPaint]'s tokens (off: a `switchBorder` border in `textFaint`); the thumb
 *  at [SwitchGeometry.thumbCentreX], sliding `switchTravel` ease-in-out, snapped under a moment's still rule (remove
 *  animations or Less motion; D-239: never battery saver). Dimmed to [ButtonLook.DISABLED_ALPHA] when disabled. Silent to TalkBack: its row is
 *  the Switch. The twin of iOS's `ThemeSwitch`. */
@Composable
fun ThemeSwitch(isOn: Boolean, dark: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val theme = Appearance.theme
    val paint = SwitchPaint.of(isOn)
    val width = ThemeArt.m("switchWidth")
    val height = ThemeArt.m("switchHeight")
    val thumb = ThemeArt.m("switchThumb")
    val inset = ThemeArt.m("switchInset")
    val motion = rememberMotionState()
    val still = motion.reduce
    val centre by animateFloatAsState(
        SwitchGeometry.thumbCentreX(isOn),
        if (still) snap() else tween((ThemeArt.m("switchTravel") * 1000).roundToInt(), easing = EaseInOut),
        label = "switchThumb",
    )
    val track = ThemeArt.pillShape(theme)
    val thumbShape = tileShape(TileShape.of(theme, person = true), thumb.toInt())
    val border = paint.border
    Box(
        modifier
            .size(width.dp, height.dp)
            .alpha(if (enabled) 1f else ButtonLook.DISABLED_ALPHA)
            .clearAndSetSemantics {},
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(paint.track(dark), track)
                .then(if (border != null) Modifier.border(ThemeArt.m("switchBorder").dp, border(dark), track) else Modifier),
        )
        Box(
            Modifier
                .offset(x = (centre - thumb / 2).dp, y = inset.dp)
                .size(thumb.dp)
                .shadow(ThemeArt.m("segmentShadowRadius").dp, thumbShape)
                .background(paint.thumb(dark), thumbShape),
        )
    }
}

/** D-253 (the store look, shot 4): what a field row shows. A LABELLED row (a split kind's person row: Exact, Percent,
 *  Shares, Adjustment) keeps its label, the person's name, at its start whatever is typed, and its field trails, so
 *  "1500" never stands alone with the placeholder gone; its prompt is the placeholder's first words ("Amount" of
 *  "Amount for Meera"), and TalkBack still hears the whole placeholder. A row with no label is today's. The twin of
 *  iOS's `FieldRowShows` in ListRows.swift. */
object FieldRowShows {
    /** The prompt in the field: the placeholder, or for a labelled row the words before " for ". */
    fun prompt(placeholder: String, label: String?): String =
        if (label == null || " for " !in placeholder) placeholder else placeholder.substringBefore(" for ")
}

/** D-225 (Designs 1, 11, 12): a field as a list row: a plain field padded `rowPadV` at the 48 dp minimum, the
 *  [placeholder] in `textFaint` (and its name to TalkBack), the accent caret, an optional [trailing] piece (a clear
 *  cross) a row gap after it. The padding and the minimum height sit INSIDE the field's decoration box (T3 review), and
 *  the [label] and the field share one tap area that focuses the field (D-253 review M3), so a tap anywhere in the 48 dp
 *  row but the trailing piece focuses the field, as iOS's row does. [spoken] names the field to TalkBack when the
 *  placeholder alone would not (D-225 T7: New group's "Person 2", iOS's `accessibilityLabel`). The twin of iOS's
 *  `FieldRow`. */
@Composable
fun FieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body(dark),
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    spoken: String = placeholder,
    trailing: (@Composable () -> Unit)? = null,
    /** D-245: the welcome's name field is centred (iOS's `.multilineTextAlignment(.center)`): the placeholder spans the
     *  row so the style's centred alignment applies to it too. */
    centred: Boolean = false,
    /** D-248 (U29.2): the text field's own modifier (a form that moves the keyboard between rows hangs its
     *  `focusRequester` here; [modifier] is the whole row's). */
    fieldModifier: Modifier = Modifier,
    /** D-253: the person's name before the field, drawn whatever is typed; null for an unlabelled row. */
    label: String? = null,
) {
    val trails = label != null
    val prompt = FieldRowShows.prompt(placeholder, label)
    val focus = remember { FocusRequester() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // D-253 review (M3): the name and the field are one tap area that focuses the field (iOS's row tap); the field
        // takes its own taps first, and the clear cross after them keeps its own.
        Row(
            Modifier.weight(1f).pointerInput(focus) { detectTapGestures { focus.requestFocus() } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // D-253: the person's name, as a list row's title (`Type.name(dark, 16)`), sharing the row half and half with the
            // field (a value row's layout), typed into or not; TalkBack hears the field's whole name instead.
            label?.let { shown ->
                Text(
                    shown, style = Type.name(dark, 16).copy(color = Tok.text(dark)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {},
                )
                Spacer(Modifier.width(ThemeArt.m("rowGap").dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = if (trails) style.copy(textAlign = TextAlign.End) else style,
                cursorBrush = SolidColor(Tok.accent(dark)),
                keyboardOptions = keyboard,
                keyboardActions = actions,
                modifier = Modifier.weight(1f).focusRequester(focus).then(fieldModifier).semantics { contentDescription = spoken },
                decorationBox = { field ->
                    Box(
                        Modifier
                            .heightIn(min = ThemeArt.m("touchTarget").dp)
                            .then(if (centred || trails) Modifier.fillMaxWidth() else Modifier)
                            .padding(vertical = ThemeArt.m("rowPadV").dp),
                        // D-253: a labelled row's prompt and words sit at the row's end.
                        contentAlignment = if (trails) Alignment.CenterEnd else Alignment.CenterStart,
                        // D-245: centred, the placeholder and the typed text both span the row, so the style centres them.
                        propagateMinConstraints = centred,
                    ) {
                        if (value.isEmpty()) Text(prompt, style = style.copy(color = Tok.textFaint(dark)))
                        field()
                    }
                },
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(ThemeArt.m("rowGap").dp))
            trailing()
        }
    }
}

/** D-225 (Design 9): iOS's `HairlineFieldStyle` on Android: a plain field padded 10 vertically with a 1 dp `hairline`
 *  under it, no outline; the [placeholder] in `textFaint`. */
@Composable
fun HairlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body(dark),
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    /** D-245 (the brief's twin defect): the keyboard's action (Profile's Done saves the name, as iOS's Return does). */
    actions: KeyboardActions = KeyboardActions.Default,
) {
    val line = Tok.hairline(dark)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Tok.accent(dark)),
        keyboardOptions = keyboard,
        keyboardActions = actions,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = placeholder }
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(line, topLeft = Offset(0f, size.height - px), size = Size(size.width, px))
            },
        decorationBox = { field ->
            // iOS's 10, then the text field's own inset (D-225 follow-up R3: iOS's text field draws its line 1 pt in).
            Box(Modifier.padding(vertical = 10.dp).padding(vertical = ListRows.FIELD_INSET.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Tok.textFaint(dark)))
                field()
            }
        },
    )
}

/** D-225 (Design 2): the explanation under a card (what a Form footer used to say): the detail size in `textDim` on the
 *  page ground (art.py 41: 4.5 on every ground's worst pixel); the caller sets it 8 under its card. The twin of iOS's
 *  `CardCaption`. */
@Composable
fun CardCaption(text: String, dark: Boolean, modifier: Modifier = Modifier) {
    Text(text, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp), modifier = modifier)
}

/** One option of a [ChoicePage]: what is stored, its label, and an optional summary as parts. */
data class ChoiceOption<T>(val value: T, val label: String, val parts: List<String> = emptyList())

/** D-225 (Design 10): a choice a form makes, as a pushed page: one [ListCard] of check rows, the [chosen] one checked,
 *  an optional [caption] under the card; picking writes ([onPick]) and returns ([onClose]), unless the option [stays]
 *  (it waits on a confirmation the caller raises: App lock's Off). A radio group to TalkBack
 *  (the card `selectableGroup()`, each row a RadioButton). Drawn in [PushedWindow], as the currency list. The twin of
 *  iOS's `ChoicePage`. */
@Composable
fun <T> ChoicePage(
    title: String,
    options: List<ChoiceOption<T>>,
    chosen: T?,
    dark: Boolean,
    onPick: (T) -> Unit,
    onClose: () -> Unit,
    caption: String? = null,
    stays: (T) -> Boolean = { false },
) {
    PushedWindow(title, dark, onClose) {
        ListCard(dark, Modifier.selectableGroup()) {
            options.forEach { option ->
                row {
                    ListRow(option.label, dark, parts = option.parts, trailing = RowTrailing.Check(option.value == chosen)) {
                        onPick(option.value)
                        if (!stays(option.value)) onClose()
                    }
                }
            }
        }
        caption?.let { CardCaption(it, dark) }
    }
}

/** D-220 (L-D217-6), extracted in D-225 (Design 10): a page pushed in a window of its own, for a list that opens from
 *  several sheets (iOS pushes it inside the sheet). The window under the system bars with no dim, entering from the end
 *  in iOS's 250 ms push (none under "remove animations"), the art ground, a scrolling column of [content] 8 apart under
 *  the bar, and the bar titled [title] with the page's backdrop. The currency list and [ChoicePage] draw in it. */
@Composable
fun PushedWindow(title: String, dark: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val motion = rememberMotionState()
    val shown = remember { MutableTransitionState(false) }
    // D-250: it leaves as it came (iOS's pop): system Back, the bar's back or a pick slides it out to the trailing edge
    // first, then closes the window; reduced, it closes at once.
    var closing by remember { mutableStateOf(false) }
    val close = {
        if (motion.reduce) {
            onClose()
        } else {
            closing = true
            shown.targetState = false
        }
    }
    LaunchedEffect(shown) {
        snapshotFlow { closing && shown.isIdle && !shown.currentState }.first { it }
        onClose()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SkiptiWindowLocals {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            SideEffect {
                // No dimmed status bar: the page covers the window, as a pushed page covers the screen. The bar icons
                // follow SKIPTI's mode, as the activity's do (D-184).
                window?.setDimAmount(0f)
                window?.let {
                    WindowCompat.getInsetsController(it, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            LaunchedEffect(Unit) { shown.targetState = true }
            val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
            AnimatedVisibility(
                visibleState = shown,
                enter = if (motion.reduce) EnterTransition.None else slideInHorizontally(ChangeMotion.tween(ChangeMotion.Kind.PAGE)) { w -> if (ltr) w else -w },
                exit = if (motion.reduce) ExitTransition.None else slideOutHorizontally(ChangeMotion.tween(ChangeMotion.Kind.PAGE)) { w -> if (ltr) w else -w },
            ) {
                PushedWindowPage(title, dark, close, content)
            }
        }
    }
}

@Composable
private fun PushedWindowPage(title: String, dark: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val backdrop = rememberBackdrop()
    // The art runs under the system bars; the page keeps clear of them (and of the keyboard) itself.
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .backdropSource(backdrop)
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = BarDecisions.formTop(headerless = true).dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
            PushedBar(title, onBack = onClose, scroll = scroll, backdrop = backdrop)
        }
    }
}
