package com.vardrlabs.skipti

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** D-179: the four tabs and the raised + between Groups and Expenses. The
 *  labels are iOS's `HomeTab` labels, verbatim; HomeTabsTest pins both. */
enum class HomeTab(val label: String, val icon: ImageVector, val rune: String) {
    BALANCE("Balance", TabIcons.Wallet, Runes.BALANCE),
    EXPENSES("Expenses", TabIcons.Receipt, Runes.EXPENSES),
    GROUPS("Groups", TabIcons.Users, Runes.GROUPS),
    SETTLE("Settle up", TabIcons.CircleCheck, Runes.SETTLE);

    companion object {
        /** The + is not a tab; this is its accessibility label. */
        const val ADD_LABEL = "Add an expense"

        /** The bar, left to right: null is the raised +. */
        /** D-180: Expenses and Groups swapped (the founder, 2026-09-29). */
        val bar: List<HomeTab?> = listOf(BALANCE, EXPENSES, null, GROUPS, SETTLE)
    }
}

/** D-205 (the founder, 2026-10-13): what a tap on a tab does; the twin of
 *  iOS's TabTap. The tab you are in goes back to its first screen, and there
 *  a tap scrolls to the top. `covered`: tabs with a page open over the root. */
sealed interface TabTap {
    data class SwitchTo(val tab: HomeTab) : TabTap
    data class PopToRoot(val tab: HomeTab) : TabTap
    data class ScrollToTop(val tab: HomeTab) : TabTap

    companion object {
        fun decide(selected: HomeTab, tapped: HomeTab, covered: Set<HomeTab>): TabTap = when {
            tapped != selected -> SwitchTo(tapped)
            tapped in covered -> PopToRoot(tapped)
            else -> ScrollToTop(tapped)
        }
    }
}

/** D-205: bumped when the shown tab is tapped again at its first screen. */
val LocalScrollTop = compositionLocalOf { 0 }

/** D-205's re-tap, as a decision: a tab root goes to the top when the signal changed since it came on screen
 *  ([atEntry]), never for a signal it found there (D-223 fix wave, F6: a kept scroll state that comes back keeps its
 *  offset). */
object ShellScrollTop {
    fun scrolls(signal: Int, atEntry: Int): Boolean = signal > 0 && signal != atEntry
}

/** D-220 (L-D217-3): the tab bar overlays the shell's page, as iOS's does (so its glass has the page under it); a page
 *  in the shell keeps this much clear at its end (the bar's measured height). 0 outside the shell. */
val LocalShellBarInset = compositionLocalOf { 0.dp }

/** D-225 T7 (A5, from D-223's fix re-review m4): a tab root's place, kept by the shell above the route switch as Home's
 *  [HomeKeep] is: its [scroll] state (saved like `route`) and the last read it drew from (memory only), so the tab's
 *  first frame back from a pushed route, a group's page or another tab draws the same rows at once and the kept offset
 *  is never clamped by an empty first frame. iOS keeps each tab's stack, and its place, the same way. Expenses, Groups
 *  and Settle up each hold one; a screen keeps its read in [slot]s, its passing state (a menu, an alert) in its own
 *  `remember`. */
class TabKeep(val scroll: ScrollState = ScrollState(0)) {
    private val slots = HashMap<String, androidx.compose.runtime.MutableState<*>>()

    /** The kept state named [name], made from [initial] the first time it is asked for. */
    @Suppress("UNCHECKED_CAST")
    fun <T> slot(name: String, initial: () -> T): androidx.compose.runtime.MutableState<T> =
        slots.getOrPut(name) { androidx.compose.runtime.mutableStateOf(initial()) } as androidx.compose.runtime.MutableState<T>
}

/** A tab root's scroll state: back to the top when [LocalScrollTop] changes, a jump instead of a scroll when motion is
 *  reduced ([rememberMotionState]: the phone's "remove animations" or Less motion). Compose's own animation follows only
 *  the phone's setting, so the switch needs this gate in code (D-231 fix wave, I1), as iOS's ShellBar has. */
@Composable
fun rememberShellScrollState(): ScrollState = rememberShellScrollState(rememberScrollState())

/** [state] kept by the caller (D-223 fix wave, F6: Home's, across a pushed route), back to the top on a re-tap. */
@Composable
fun rememberShellScrollState(state: ScrollState): ScrollState {
    val signal = LocalScrollTop.current
    val atEntry = remember { signal }
    val reduce = rememberMotionState().reduce
    LaunchedEffect(signal) { if (ShellScrollTop.scrolls(signal, atEntry)) { if (reduce) state.scrollTo(0) else state.animateScrollTo(0) } }
    return state
}

/**
 * D-179: SKIPTI's own tab bar (the founder's canvas, 2026-09-26), the twin
 * of iOS's `SkiptiTabBar`: Balance, Expenses, a raised +, Groups, Settle up
 * (D-180's order) on a ground per theme (D-219: frosted glass is Aurora's
 * alone; Norse and Vardr Labs are the solid surface; D-220: the glass blurs
 * the shell's page under it, [LocalShellBackdrop]), a hairline along the
 * top. The selected tab pops (a spring scale and a filled accent pill behind
 * its glyph in Aurora); reduced motion (the phone's "remove animations" or
 * Less motion, [rememberMotionState]) holds every size still and snaps, as
 * iOS's does (D-231 fix wave, I1: Compose follows only the phone's setting,
 * so each animation is gated in code). Past 1.15x font scale the labels drop and each name moves into its
 * icon's description (D-113's rule).
 */
@Composable
internal fun SkiptiTabBar(dark: Boolean, selected: HomeTab, onSelect: (HomeTab) -> Unit, onAdd: () -> Unit) {
    val showLabel = LocalDensity.current.fontScale < 1.15f
    val theme = Appearance.theme
    // D-220 (L-D217-3): Aurora's glass blurs the shell's page (API 31+) under the 0.72 film, or keeps C1's 0.94 film
    // where it cannot blur; Norse and Vardr Labs are the solid surface (film 1).
    val backdrop = LocalShellBackdrop.current
    val blur = backdrop?.content != null && TabBarGround.of(theme) == TabBarGround.GLASS
    val reduce = rememberMotionState().reduce
    Row(
        Modifier
            .fillMaxWidth()
            .backdropGlass(backdrop, Tok.surface(dark).copy(alpha = TabBarGround.film(theme, blur)), blur)
            // The hairline along the bar's top, every theme; drawn before the inset so it covers the whole bar.
            .drawBehind {
                drawRect(Tok.hairline(dark), topLeft = Offset(0f, 0f), size = Size(size.width, 1.dp.toPx()))
            }
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HomeTab.bar.forEach { tab ->
            if (tab == null) {
                // D-219 fix wave (I-1): in front of the neighbour tabs, so a face that overflows its 64 footprint
                // wins its taps.
                Box(Modifier.weight(1f).zIndex(1f).height(AddButtonGeometry.FOOTPRINT.dp), contentAlignment = Alignment.Center) {
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed && !reduce) 0.9f else 1f, if (reduce) snap() else spring(dampingRatio = 0.5f), label = "add")
                    // D-214: the hex bolt turns 30 degrees while pressed (Vardr Labs) on its own spring (iOS's response
                    // 0.45 s, damping 0.55); never when motion is reduced.
                    val turn by animateFloatAsState(
                        AddButtonMotion.turn(AddButtonArt.of(Appearance.theme), pressed, reduce),
                        if (reduce) snap() else spring(dampingRatio = AddButtonMotion.TURN_DAMPING, stiffness = AddButtonMotion.TURN_STIFFNESS), label = "turn",
                    )
                    // F1: the face takes the click after its shape's clip; this passes the placement and the press.
                    AddButtonFace(
                        art = AddButtonArt.of(Appearance.theme),
                        dark = dark,
                        interactionSource = interaction,
                        label = HomeTab.ADD_LABEL,
                        onClick = onAdd,
                        modifier = Modifier
                            .offset(y = (-AddButtonGeometry.LIFT).dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = turn },
                    )
                }
            } else {
                val on = tab == selected
                val indicator = TabIndicator.of(theme)
                // D-219 (L13): Aurora's active glyph is onAccent (its pill is filled accent); Norse's the accent; Vardr Labs' the ring.
                val activeTint = when (TabIndicator.activeGlyph(theme)) {
                    TabGlyphTint.ON_ACCENT -> Tok.onAccent(dark)
                    TabGlyphTint.ACCENT -> Tok.accent(dark)
                    TabGlyphTint.RING -> Tok.ring(dark)
                }
                val tint = if (on) activeTint else Tok.textDim(dark)
                val set = ThemeSet.resolve(theme, dark)
                val scale by animateFloatAsState(if (on && !reduce) 1.08f else 1f, if (reduce) snap() else spring(dampingRatio = 0.55f), label = tab.name)
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .size(width = 52.dp, height = 30.dp)
                            .then(
                                when {
                                    indicator == TabIndicator.PILL ->
                                        Modifier.background(if (on) Tok.accent(dark) else Color.Transparent, RoundedCornerShape(50))
                                    !on -> Modifier
                                    indicator == TabIndicator.UNDERLINE -> Modifier.drawBehind {
                                        drawRect(Tok.accent(dark), topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
                                    }
                                    else -> Modifier.border(1.dp, Tok.ring(dark).copy(alpha = TabIndicator.outlineAlpha(set)), RectangleShape)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (TabIndicator.drawsRunes(theme)) {
                            Text(
                                tab.rune,
                                style = Type.rune(runeSize("tabRune", LocalDensity.current)).copy(color = tint),
                                // G1: laid out at its own line height (taller than the 30 dp slot) and centred by its ink.
                                modifier = Modifier
                                    .wrapContentHeight(unbounded = true)
                                    .offset(y = ThemeArt.tabRuneInkShift.dp)
                                    .clearAndSetSemantics { if (!showLabel) contentDescription = tab.label },
                            )
                        } else {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = if (showLabel) null else tab.label,
                                tint = tint,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    if (showLabel) {
                        Text(
                            tab.label,
                            style = Type.tabLabel(dark).copy(
                                color = if (on) Tok.text(dark) else Tok.textDim(dark),
                            ),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
