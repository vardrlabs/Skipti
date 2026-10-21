package com.vardrlabs.skipti

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.roundToInt

// D-250 (decision 194): the change motion's views, the twins of iOS's `RowsMotion`, `Reveal`, `MoneyChange`, `FillMotion`,
// `ThemeCrossfade` and the system's own pushes and sheets. Every one reads the door (`rememberMotionState().reduce`, Reduce
// Motion or Less motion): reduced, a cut. None reads power saving (D-239: only the art does). The values are
// [ChangeMotion]'s.

/** D-250: a page's way in and out ([PageMove]): a push slides in from the trailing edge while the page under it shifts
 *  a third, a back reverses it (the page coming back under the one leaving), a sheet rises from the bottom over what
 *  stays, and falls back. A cut when [reduce] (or [PageMove.CUT]). RTL-aware (`Start` / `End`). */
fun AnimatedContentTransitionScope<*>.pageTransform(move: PageMove, reduce: Boolean): ContentTransform {
    if (reduce || move == PageMove.CUT) return EnterTransition.None togetherWith ExitTransition.None
    val spec = ChangeMotion.tween<IntOffset>(ChangeMotion.Kind.PAGE)
    val start = AnimatedContentTransitionScope.SlideDirection.Start
    val end = AnimatedContentTransitionScope.SlideDirection.End
    return when (move) {
        PageMove.PUSH -> slideIntoContainer(start, spec) togetherWith slideOutOfContainer(start, spec) { it / 3 }
        PageMove.BACK -> (slideIntoContainer(end, spec) { it / 3 } togetherWith slideOutOfContainer(end, spec))
            .apply { targetContentZIndex = -1f }
        PageMove.RISE -> slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, spec) togetherWith
            ExitTransition.KeepUntilTransitionsFinished
        PageMove.FALL -> (EnterTransition.None togetherWith slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, spec))
            .apply { targetContentZIndex = -1f }
        PageMove.CUT -> EnterTransition.None togetherWith ExitTransition.None
    }
}

/** D-250: how a row leaves [MovingRows]: it fades as the rows below close the gap, or (a card that is done: a settled
 *  payment, a deleted expense) it slides out to the trailing edge as it fades, so it never sits under the rows closing
 *  up (iOS's `cardLeaves`, from the look). A row coming back (an Undo) fades in as the gap opens. */
enum class RowLeave { FADE, SLIDE }

private class RowsMemory<T> {
    var shown: List<Any> = emptyList()
    val items = HashMap<Any, T>()
    val states = HashMap<Any, MutableTransitionState<Boolean>>()
    var first = true
}

/** D-250: true inside a [MovingRows] row that is on its way out, so a row that would redraw itself with a state that has
 *  moved on (Settle up's card dropping "Recording…" once its payment is gone) keeps looking as it last did, as SwiftUI's
 *  removed view does. */
val LocalRowLeaving = compositionLocalOf { false }

/** D-250: the twin of iOS's `RowsMotion` over a list: [items] keyed by [key] (STABLE: never a position), each row in and
 *  out over `ChangeMotion.CHANGE`; a row that left is drawn (as it last was) until its way out ends, at its place
 *  ([RowsMotion.merge]). The rows on first sight are simply there. [row] gets the item and its index among the rows
 *  drawn (for a divider above all but the first). Reduced: a cut. */
@Composable
fun <T> MovingRows(
    items: List<T>,
    key: (T) -> Any,
    leave: RowLeave = RowLeave.FADE,
    spacing: Dp = 0.dp,
    row: @Composable (item: T, index: Int) -> Unit,
) {
    val motion = rememberMotionState()
    val memory = remember { RowsMemory<T>() }
    var finished by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") finished
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val nowKeys = items.map(key)
    val nowSet = nowKeys.toHashSet()
    items.forEach { memory.items[key(it)] = it }
    val keys = if (motion.reduce) nowKeys else RowsMotion.merge(memory.shown.filter { it in memory.states }, nowKeys)
    for (k in keys) {
        val want = k in nowSet
        val state = memory.states.getOrPut(k) { MutableTransitionState(memory.first || motion.reduce) }
        if (state.targetState != want) state.targetState = want
    }
    val drawn = keys.toHashSet()
    memory.states.keys.retainAll(drawn)
    memory.items.keys.retainAll(drawn)
    memory.shown = keys
    memory.first = false
    val ms = ChangeMotion.millis(ChangeMotion.Kind.CHANGE)
    val ease = ChangeMotion.easing(ChangeMotion.Kind.CHANGE)
    // One block in the caller's stack (none while there are no rows); the gap between rows ([spacing], the stack's own)
    // belongs to the row under it, so it opens and closes with that row instead of jumping when the row has gone.
    if (keys.isEmpty()) return
    Column {
    keys.forEachIndexed { i, k ->
        val state = memory.states.getValue(k)
        @Suppress("UNCHECKED_CAST") val item = memory.items[k] as T
        key(k) {
            AnimatedVisibility(
                visibleState = state,
                enter = if (motion.reduce) EnterTransition.None else
                    fadeIn(androidx.compose.animation.core.tween(ms, easing = ease)) +
                        expandVertically(androidx.compose.animation.core.tween(ms, easing = ease), expandFrom = Alignment.Top),
                exit = if (motion.reduce) ExitTransition.None else when (leave) {
                    RowLeave.FADE -> fadeOut(androidx.compose.animation.core.tween(ms, easing = ease)) +
                        shrinkVertically(androidx.compose.animation.core.tween(ms, easing = ease), shrinkTowards = Alignment.Top)
                    RowLeave.SLIDE -> slideOutHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { w -> if (ltr) w else -w } +
                        fadeOut(androidx.compose.animation.core.tween(ms, easing = ease)) +
                        shrinkVertically(androidx.compose.animation.core.tween(ms, easing = ease), shrinkTowards = Alignment.Top)
                },
            ) {
                CompositionLocalProvider(LocalRowLeaving provides !state.targetState) {
                    if (i > 0 && spacing > 0.dp) Column(Modifier.padding(top = spacing)) { row(item, i) } else row(item, i)
                }
            }
            if (!state.targetState) {
                LaunchedEffect(state) {
                    snapshotFlow { state.isIdle && !state.currentState }.first { it }
                    memory.states.remove(k)
                    memory.items.remove(k)
                    finished++
                }
            }
        }
    }
    }
}

/** D-250: a line that appears (an error, a confirmation): the twin of iOS's `Reveal` + `.reveal`. It rises
 *  `ChangeMotion.RISE` and fades in over the toast's way in (`ChangeMotion.NUMBER`, its ease) the first time it is
 *  composed, the space under it opening; it leaves at once (the caller's `if` takes it away). Reduced: a cut. */
@Composable
fun RevealOnEnter(content: @Composable () -> Unit) {
    val motion = rememberMotionState()
    val shown = remember { MutableTransitionState(motion.reduce) }
    shown.targetState = true
    val ms = ChangeMotion.millis(ChangeMotion.Kind.NUMBER)
    val ease = ChangeMotion.easing(ChangeMotion.Kind.NUMBER)
    val rise = with(LocalDensity.current) { ChangeMotion.RISE.dp.roundToPx() }
    AnimatedVisibility(
        visibleState = shown,
        enter = if (motion.reduce) EnterTransition.None else
            fadeIn(androidx.compose.animation.core.tween(ms, easing = ease)) +
                slideInVertically(androidx.compose.animation.core.tween(ms, easing = ease)) { rise } +
                expandVertically(androidx.compose.animation.core.tween(ms, easing = ease), expandFrom = Alignment.Top),
        exit = ExitTransition.None,
    ) {
        content()
    }
}

/** D-250: [visible] lines and blocks that come and go in place (a hint, a confirmation, a field row): the same way in
 *  as [RevealOnEnter], leaving at once, as iOS's `.reveal`. Shown at once on first sight. Reduced: a cut. */
@Composable
fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = rememberMotionState()
    val ms = ChangeMotion.millis(ChangeMotion.Kind.NUMBER)
    val ease = ChangeMotion.easing(ChangeMotion.Kind.NUMBER)
    val rise = with(LocalDensity.current) { ChangeMotion.RISE.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        enter = if (motion.reduce) EnterTransition.None else
            fadeIn(androidx.compose.animation.core.tween(ms, easing = ease)) +
                slideInVertically(androidx.compose.animation.core.tween(ms, easing = ease)) { rise } +
                expandVertically(androidx.compose.animation.core.tween(ms, easing = ease), expandFrom = Alignment.Top),
        exit = ExitTransition.None,
    ) {
        content()
    }
}

private class MoneyMemo(var rest: AnnotatedString, var value: Long)

private class MoneyRoll(val from: AnnotatedString, val to: AnnotatedString, val down: Boolean)

/** D-250: an amount or a count that changes rolls, character by character, the twin of iOS's `.moneyChange` (SwiftUI's
 *  `numericText`): the characters that changed leave up and the new ones come in from below (down and from above when
 *  [value] falls), over `ChangeMotion.NUMBER` on the toast's ease; the ones that did not change hold still (slots keyed
 *  from the right, [MoneyChange.slots]). [value] is core's integer behind [text] (minor units, a count, basis points):
 *  never a Double from money; [text] stays core's string. At rest it is one plain [Text] (its own layout, auto size and
 *  semantics); only the roll draws per character, at the size the text last laid out at. Reduced: a cut. */
@Composable
fun MoneyText(
    text: String,
    value: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    autoSize: TextAutoSize? = null,
    textAlign: TextAlign? = null,
) = MoneyText(AnnotatedString(text), value, style, modifier, maxLines, softWrap, overflow, autoSize, textAlign)

/** D-250: [MoneyText] for words with spans (a ledger card's line: the amount in its own colour); each character keeps
 *  its span while it rolls. */
@Composable
fun MoneyText(
    text: AnnotatedString,
    value: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    autoSize: TextAutoSize? = null,
    textAlign: TextAlign? = null,
) {
    val reduce = rememberMotionState().reduce
    val memo = remember { MoneyMemo(text, value) }
    var laidOut by remember { mutableStateOf(TextUnit.Unspecified) }
    val roll = remember(text) {
        if (!reduce && text.text != memo.rest.text) MoneyRoll(memo.rest, text, MoneyChange.countsDown(memo.value, value)) else null
    }
    memo.rest = text
    memo.value = value
    key(text) {
        var going by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { going = true }
        val p by animateFloatAsState(if (going) 1f else 0f, if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.NUMBER), label = "roll")
        if (roll != null && p < 1f) {
            MoneyRollRow(roll, p, if (laidOut.isSpecified) style.copy(fontSize = laidOut) else style, modifier)
        } else {
            Text(
                text,
                style = style,
                modifier = modifier,
                maxLines = maxLines,
                softWrap = softWrap,
                overflow = overflow,
                autoSize = autoSize,
                textAlign = textAlign,
                onTextLayout = { laidOut = it.layoutInput.style.fontSize },
            )
        }
    }
}

/** The roll itself: one slot per character of the longer string, keyed from the right; a slot whose character changed
 *  draws the old one leaving and the new one arriving, its width easing between theirs. */
@Composable
private fun MoneyRollRow(roll: MoneyRoll, p: Float, style: TextStyle, modifier: Modifier) {
    val n = max(roll.from.length, roll.to.length)
    val sign = if (roll.down) -1f else 1f
    fun at(s: AnnotatedString, j: Int): AnnotatedString? = (s.length - 1 - j).takeIf { it >= 0 }?.let { s.subSequence(it, it + 1) }
    Row(modifier.clearAndSetSemantics { text = roll.to }) {
        for (j in n - 1 downTo 0) {
            val old = at(roll.from, j)
            val new = at(roll.to, j)
            if (old?.text == new?.text && new != null) {
                Text(new, style = style, maxLines = 1, softWrap = false)
            } else {
                Layout(
                    content = {
                        Text(old ?: AnnotatedString(""), style = style, maxLines = 1, softWrap = false, modifier = Modifier.graphicsLayer {
                            alpha = 1f - p
                            translationY = -sign * p * size.height * 0.5f
                        })
                        Text(new ?: AnnotatedString(""), style = style, maxLines = 1, softWrap = false, modifier = Modifier.graphicsLayer {
                            alpha = p
                            translationY = sign * (1f - p) * size.height * 0.5f
                        })
                    },
                ) { measurables, constraints ->
                    val loose = Constraints(maxHeight = constraints.maxHeight)
                    val a = measurables[0].measure(loose)
                    val b = measurables[1].measure(loose)
                    val w = (a.width + (b.width - a.width) * p).roundToInt()
                    val h = max(a.height, b.height)
                    val base = b[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: a[FirstBaseline]
                    layout(w, h, mapOf(FirstBaseline to base, LastBaseline to base)) {
                        a.place(0, 0)
                        b.place(0, 0)
                    }
                }
            }
        }
    }
}

/** D-250: a meter's or You's ring's share drawn now: the twin of iOS's `.fillMotion`. It sweeps from the share shown to
 *  [value] over `ChangeMotion.FILL` on the welcome's ease; the first known value lands at once (no sweep from empty on
 *  every visit); null is not read yet. [memory] keeps the last share drawn across a fresh composition (You's ring, drawn
 *  afresh when you come back from one of You's pages), so it sweeps from it. A moment's rule: reduced, a cut; power
 *  saving plays. */
@Composable
fun rememberFill(value: Double?, memory: String? = null): Float {
    val reduce = rememberMotionState().reduce
    var target by remember { mutableStateOf(memory?.let { FillMotion.shown[it] } ?: value) }
    var sweeping by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value == null) return@LaunchedEffect
        sweeping = FillMotion.sweeps(reduce, target)
        target = value
        memory?.let { FillMotion.shown[it] = value }
    }
    val shown by animateFloatAsState(
        (target ?: 0.0).toFloat(),
        if (reduce) snap() else if (sweeping) ChangeMotion.tween(ChangeMotion.Kind.FILL) else snap(),
        label = "fill",
    )
    return shown
}

/** D-250: where a segmented control's segments sit, so one chosen pill can slide between them ([SlidingPill]). */
class PillPlaces {
    internal var container: LayoutCoordinates? = null
    internal val segments = HashMap<Int, LayoutCoordinates>()
    internal val at = mutableStateMapOf<Int, Rect>()

    internal fun measure() {
        val box = container ?: return
        if (!box.isAttached) return
        for ((i, c) in segments) if (c.isAttached) {
            val r = box.localBoundingBoxOf(c, clipBounds = false)
            if (at[i] != r) at[i] = r
        }
    }
}

/** The box holding the pill and the segments: the pill is laid out against it. */
fun Modifier.pillTrack(places: PillPlaces): Modifier = onGloballyPositioned { places.container = it; places.measure() }

/** Segment [index]: the pill takes its bounds at this point of its modifier chain. */
fun Modifier.pillPlace(places: PillPlaces, index: Int): Modifier = onGloballyPositioned { places.segments[index] = it; places.measure() }

/** D-250: the chosen segment's pill, ONE pill that slides to the chosen segment over `ChangeMotion.CHANGE` ease-in-out
 *  (the switch thumb's travel), the twin of iOS's `matchedGeometryEffect` pill (Just me / All, Chart / Table, Light or
 *  dark). Drawn first in the box marked [pillTrack], under the segments. Reduced: it jumps. */
@Composable
fun SlidingPill(places: PillPlaces, chosen: Int, shape: Shape, colour: Color, shadow: Dp = 0.dp, modifier: Modifier = Modifier) {
    val reduce = rememberMotionState().reduce
    val target = places.at[chosen] ?: return
    val left by animateFloatAsState(target.left, if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.CHANGE), label = "pillLeft")
    val top by animateFloatAsState(target.top, if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.CHANGE), label = "pillTop")
    val width by animateFloatAsState(target.width, if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.CHANGE), label = "pillWidth")
    val height by animateFloatAsState(target.height, if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.CHANGE), label = "pillHeight")
    Box(
        modifier
            .layout { measurable, _ ->
                val w = width.roundToInt().coerceAtLeast(0)
                val h = height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(0, 0) { placeable.place(left.roundToInt(), top.roundToInt()) }
            }
            .then(if (shadow > 0.dp) Modifier.shadow(shadow, shape) else Modifier)
            .background(colour, shape),
    )
}

/** D-250: the person's theme or mode change crossfades the whole window from a picture of the old look over
 *  `ChangeMotion.FADE`, the twin of iOS's `ThemeCrossfade`. Only a change the person made (a theme card, the mode
 *  control, the welcome's look): never at launch, never sync applying another phone's setting (D-244). The picture
 *  outlives the activity a mode change makes again (D-232), so the new one fades from it. Reduced: a cut. */
object ThemeCrossfade {
    /** The old look, while it fades. Memory only. */
    internal var pending by mutableStateOf<ImageBitmap?>(null)

    /** Applies [change], crossfading from the window as it looked before (a cut when [reduce], or when no picture can
     *  be taken: the change is never held back by it). */
    fun change(view: View, reduce: Boolean, change: () -> Unit) {
        val window = view.context.activity()?.window
        val root = window?.decorView
        if (reduce || window == null || root == null || root.width <= 0 || root.height <= 0 || Build.VERSION.SDK_INT < 26) {
            change()
            return
        }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) pending = bitmap.asImageBitmap()
            change()
        }, Handler(Looper.getMainLooper()))
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}

/** The fading picture, over the whole window (the activity's content root); it takes no touch. */
@Composable
fun ThemeCrossfadeOverlay() {
    val shot = ThemeCrossfade.pending ?: return
    val reduce = rememberMotionState().reduce
    var gone by remember(shot) { mutableStateOf(false) }
    LaunchedEffect(shot) {
        withFrameNanos { }
        gone = true
    }
    val alpha by animateFloatAsState(
        if (gone) 0f else 1f,
        if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.FADE),
        label = "themeFade",
        finishedListener = { if (it == 0f && ThemeCrossfade.pending === shot) ThemeCrossfade.pending = null },
    )
    Image(
        shot, contentDescription = null, contentScale = ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.clearAndSetSemantics {},
    )
}

/** D-250: a button's dimming as it is enabled or disabled ([ButtonLook.DISABLED_ALPHA]): it brightens and dims over
 *  `ChangeMotion.CHANGE` (Add expense's button as soon as an amount is typed); a press stays instant (the ripple). The
 *  twin of iOS's `.rowsMotion(isEnabled)` in the button bodies. Reduced: a cut. */
@Composable
fun enabledAlpha(enabled: Boolean): Float {
    val reduce = rememberMotionState().reduce
    val alpha by animateFloatAsState(
        if (enabled) 1f else ButtonLook.DISABLED_ALPHA,
        if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.CHANGE),
        label = "enabled",
    )
    return alpha
}

/** D-250: a card whose height changes as its rows come and go (a form's card) eases to its new height over
 *  `ChangeMotion.CHANGE` instead of jumping (iOS's `.rowsMotion` on the form's stack). Reduced: a cut. */
@Composable
fun Modifier.movingSize(): Modifier {
    val reduce = rememberMotionState().reduce
    return animateContentSize(if (reduce) snap() else ChangeMotion.tween(ChangeMotion.Kind.CHANGE))
}
