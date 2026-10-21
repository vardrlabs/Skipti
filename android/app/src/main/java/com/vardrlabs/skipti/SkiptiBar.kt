package com.vardrlabs.skipti

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** D-217 Task 4: the three bars iOS 26 and 27 draw. A tab root's large title lives in the content and folds into the
 *  bar; a pushed page has a round back button; a sheet has Cancel and Done pills. */
enum class BarForm { TAB_ROOT, PUSHED, SHEET }

/**
 * D-217 Task 4: Android's navigation bar, the twin of iOS's (iOS 26/27: a tab root's 34 pt title folds into a centred
 * 17 pt inline title as the content scrolls under the bar; a pushed screen has a round glass back button and the
 * centred 17 pt title, no parent title beside it; a sheet has Cancel and Done pill buttons and the title centred).
 * Before this Android had no bar at all: titles were text inside the scrolling content and "Back" a text button.
 *
 * The decisions are here, pure and tested (SkiptiBarTest); the composables below only draw them. Every tab root and
 * pushed screen draws through it (Tasks 6 and 7), and so does every sheet (Task 8, [SheetPage]). Insets: MainActivity's one `safeDrawing` padding keeps the whole app
 * clear of the status bar (D-184), so the bar starts below it and adds nothing of its own.
 */
object BarDecisions {
    /** The bar's height, dp (a UINavigationBar is 44 pt). */
    const val BAR_HEIGHT = 44
    /** The round back button, dp (iOS 26's glass circle measures 44.7 pt, D-217 look fix 3); its touch target is
     *  [TOUCH_TARGET]. The account glyph's circle is the same ([ACCOUNT_CIRCLE_SIZE]). D-220 fix wave (F4): 44, the bar's
     *  height, as iOS 27's circle measures 44.0 pt tall (44.7 across) on the T6 look's group page. */
    const val BACK_BUTTON_SIZE = 44
    /** The account glyph's circle, dp: iOS puts the 30 pt badge in the same glass circle as the back button. */
    const val ACCOUNT_CIRCLE_SIZE = 44
    /** D-220 (A1): round a picture or initials iOS 26 draws a CAPSULE, not the circle: 51.3 x 44.3 pt measured on the
     *  D-219 pairs (.drafts/d220/t5/a1-measure.py): the 30 pt badge and 10.7 pt each side. The plain glyph keeps the
     *  45 dp circle. */
    const val ACCOUNT_BUBBLE_WIDTH = 51
    const val ACCOUNT_BUBBLE_HEIGHT = 44

    fun accountBubbleWidth(badge: Boolean): Int = if (badge) ACCOUNT_BUBBLE_WIDTH else ACCOUNT_CIRCLE_SIZE
    fun accountBubbleHeight(badge: Boolean): Int = if (badge) ACCOUNT_BUBBLE_HEIGHT else ACCOUNT_CIRCLE_SIZE

    /** D-220 (A1): iOS 26's bar glass (the back button's circle and the account item alike) reads as white over what is
     *  under it: 0.126 to 0.134 in the dark sets, 0.253 to 0.369 in the light ones, measured on the D-219 pairs
     *  (.drafts/d220/t5/a1-measure.py). The system glass cannot be restyled; this is its nearest flat match. */
    fun glassFillAlpha(dark: Boolean): Float = if (dark) 0.13f else 0.31f

    /** D-220 fix wave (F4): the bar's side margin, dp, and where iOS 27 centres its bar controls from the screen's
     *  edge (measured on the T6 look: the back circle's centre 38.0 pt from the leading edge, the account item's 41.0 pt
     *  from the trailing one; Android's sat at 32). A control's 48 dp target moves in by [controlShift]. */
    const val BAR_SIDE_MARGIN = 8
    const val BACK_CENTRE_FROM_EDGE = 38
    const val ACCOUNT_CENTRE_FROM_EDGE = 41

    /** How far a control's 48 dp target sits in from the bar's side margin, dp, for its centre to be [centreFromEdge]. */
    fun controlShift(centreFromEdge: Int): Int = centreFromEdge - BAR_SIDE_MARGIN - TOUCH_TARGET / 2

    /** D-220 fix wave (F5): Aurora's navigation bar over its blur is iOS's system material: the page's glow shows through
     *  it, lifted by white, about 0.29 in light and 0.044 in dark (T6 look, Expenses scrolled against at rest at four
     *  margin points: .drafts/d220/fix/android/film-measure.py). Android's 0.72 surface film read near white. The tab
     *  bar keeps iOS's own 0.72 film ([TabBarGround.film]); a bar that cannot blur keeps the surface film. */
    fun navFilmWhite(dark: Boolean): Float = if (dark) 0.044f else 0.29f

    /** Whether the navigation bar's film is [navFilmWhite]'s white: Aurora's glass over a blur. */
    fun navFilmIsWhite(theme: AppTheme, blur: Boolean): Boolean = blur && TabBarGround.of(theme) == TabBarGround.GLASS

    /** D-226 (A2, .drafts/d226/art.py section 54): what Aurora's dark navigation bar shows of the page under it.
     *  [navFilmWhite]'s 0.044 is a lift, no veil, so the hero's light Settle up button came through nearly as bright as it
     *  is and the collapsed title fell to 2.08:1 (Task 0; iOS's material darkens it, 4.99). Fix wave 2 (F2, art.py 55):
     *  the bar caps every pixel under it at this colour, per channel (a Darken over the blurred page on the art, under
     *  the film), so light content reads as iOS's band (#53696A, within a step) and nothing darker changes; the title's
     *  worst ground is the film over the cap itself, 5.25:1 over every content (the floor 5.2: iOS's band less the film
     *  exactly gives 5.19, so the cap is one 8-bit step under it on green). Light content flattens to the cap, as iOS's
     *  material flattens it. The multiply before (0.35, 0.41, 0.41) held only iOS's hue (L* 30 against 43). Light keeps
     *  white, no cap (its white 0.29 film lightens what passes under the dark title), and so do the solid themes and a
     *  bar that cannot blur. */
    val NAV_BAND_CAP_DARK = Color(0xFF4B6163)

    fun navBandCap(theme: AppTheme, dark: Boolean, blur: Boolean): Color =
        if (dark && navFilmIsWhite(theme, blur)) NAV_BAND_CAP_DARK else Color.White
    /** The picture or initials inside the account circle (iOS `ProfileBadge(size: 30)`), and the plain person glyph's
     *  share of it (`LucideIcon(size: size * 0.73)`). */
    const val ACCOUNT_BADGE_SIZE = 30
    const val ACCOUNT_GLYPH_SCALE = 0.73f

    /** Where a round control of [sizeDp] starts down the 44 dp bar, dp: centred on it, so a 45 dp circle starts half a
     *  dp ABOVE the bar's top (iOS's circle is 44.7 pt on a 44 pt bar). */
    fun roundTop(sizeDp: Int): Float = (BAR_HEIGHT - sizeDp) / 2f
    const val TOUCH_TARGET = 48

    /** D-219 fix wave (look defect 1): dp per Lucide unit for the back chevron, iOS's `BarTint.backChevronScale` (read from
     *  ThemeArt.swift by a test): the chevron's centreline is 12 units, so 15 dp, the height of the 17 pt SF chevron it
     *  replaced; with the stroke the ink is (12 + 2.4) * 1.25 = 18.0 tall and (6 + 2.4) * 1.25 = 10.5 wide, the drawing
     *  iOS 17 to 25 shows (iOS 26 and later show the system's glass chevron, which the app cannot restyle). */
    const val BACK_CHEVRON_SCALE = 1.25f

    /** The back chevron's icon box, dp: the 24-unit Lucide viewport at [BACK_CHEVRON_SCALE] (30 dp, inside the 45 dp circle). */
    fun backChevronDp(): Float = 24f * BACK_CHEVRON_SCALE
    /** D-217 final fix: the bar's place in TalkBack's order among its siblings (the page's content, at the default 0):
     *  first, as VoiceOver reads iOS's navigation bar before the page. */
    const val BAR_TRAVERSAL_INDEX = -1f
    /** Where a TAB ROOT's scrolling content starts below the bar's top: the bar and 8 dp, one value for the scaffold,
     *  its collapse arithmetic and the proof rows (the tab root's large title lives in the content and matches iOS's). */
    const val CONTENT_TOP_PAD = BAR_HEIGHT + 8

    // ---- D-217 look fix 1: where the first content starts under the bar, one value per kind of iOS container ----
    // Measured on iOS 27 (iPhone 17) against the look's pairs, from the bar's title line to the first card or line:
    // a pushed ScrollView page draws its first content 17.5 pt (padding 16) or 21 pt (padding 20) lower than the 8 dp
    // Android had, so iOS 26's inline bar leaves the scroll content's origin 9.5 pt under the 44 pt bar and the page's
    // own padding comes on top. A Form (grouped list) starts its first section header 20 pt under the bar (a sheet's
    // "Group" label, +12 over the old 8) and the header is 24 pt tall (Group settings' headerless first card, +36).

    /** The scroll content's origin under the bar, iOS 26 and 27, dp. */
    const val IOS_BAR_CLEARANCE = 9.5f
    /** A Form's first section header, dp under the bar, and the header's height. */
    const val FORM_HEADER_GAP = 20f
    const val FORM_HEADER_HEIGHT = 24f
    /** A sheet's LARGE title (Add and Edit expense) sits 4.5 pt lower under its Cancel than a tab root's does. */
    const val LARGE_SHEET_TITLE_EXTRA = 4.5f
    /** A tab root's ScrollView padding: Groups 8, Balance, Expenses and Settle up 16. The larger one puts the first
     *  content that much lower under the title, in every theme (Norse's ornament is placed by its own offset and does
     *  not cancel it: measured, Norse's Balance was 8 pt tight too). */
    const val TAB_ROOT_BASE_PADDING = 8
    const val TAB_ROOT_ROOMY_PADDING = 16

    /** A pushed page or sheet that scrolls a VStack at `.padding(side)` (iOS ScrollView): dp from the bar's top. */
    fun scrollTop(sidePadding: Float): Float = BAR_HEIGHT + IOS_BAR_CLEARANCE + sidePadding
    /** A page that is an iOS Form: dp from the bar's top to its first section header, or to its first card when it
     *  has no header. */
    fun formTop(headerless: Boolean): Float = BAR_HEIGHT + FORM_HEADER_GAP + (if (headerless) FORM_HEADER_HEIGHT else 0f)
    /** A sheet with a large title (the title is the first child). */
    const val LARGE_SHEET_TOP = CONTENT_TOP_PAD + LARGE_SHEET_TITLE_EXTRA
    /** The extra dp a tab root puts between its title and its first content, for the iOS padding [iosPadding]. */
    fun tabRootExtra(iosPadding: Int): Int = iosPadding - TAB_ROOT_BASE_PADDING
    /** The gap between the scaffold's children (the four tab roots' own, 14 dp): also what the ornament cancels. */
    const val CONTENT_SPACING = 14
    /** The scaffold's bottom padding, dp. */
    const val CONTENT_BOTTOM_PAD = 8
    /** A pushed page's side padding, dp. iOS's pushed pages are not uniform: most draw 16 pt, and the ones that
     *  scroll a VStack at `.padding(20)` (About, Licences, a licence, You and its section pages, Profile, Invite, Join
     *  and Join paste) draw 20: each Android screen passes the width its iOS twin has ([PAGE_SIDE_WIDE]). */
    const val PAGE_SIDE = 16
    const val PAGE_SIDE_WIDE = 20

    /** How tall a state that fills the rest of a tab root should be (iOS centres Expenses' loading, error and empty
     *  states in the space between the title and the tab bar): the viewport less what is above the content (the top
     *  padding, the title and the ornament, measured as [contentTopPx]) and the bottom padding. -1 until the viewport
     *  and the content's place are both measured, so the state can wait for them instead of showing at the top. */
    fun fillRemainingPx(viewportPx: Int, topPadPx: Int, contentTopPx: Int, bottomPadPx: Int): Int =
        if (viewportPx <= 0 || contentTopPx < 0) -1 else maxOf(0, viewportPx - topPadPx - contentTopPx - bottomPadPx)
    /** The bar's text buttons: UIKit sets them in the body face at 17 (iOS `TitleStyle.barButtonAttributes`). */
    const val BAR_BUTTON_SIZE = 17f

    const val BACK_LABEL = "Back"
    const val CANCEL_LABEL = "Cancel"
    const val DONE_LABEL = "Done"
    /** D-217 final fix: the pages only a tapped push opens are sheets whose left pill says "Close" (SkiptiApp.swift). */
    const val CLOSE_LABEL = "Close"

    /** A tab root's large title is the table's 34; once it has folded into the bar, and for every pushed or sheet
     *  title, it is the inline 17. */
    fun titleSize(form: BarForm, collapsed: Boolean): Float =
        if (form == BarForm.TAB_ROOT && !collapsed) TypeScale.largeTitle else TypeScale.inlineTitle

    /** D-248 (U31, decision 186): the inline title's largest drawn size, dp. Measured so the longest fixed pushed title
     *  fits a 360 dp phone beside the back button ([INLINE_TITLE_ROOM_360]) in every theme at any font scale (DoneMomentsTest's
     *  width table, AWT on the bundled faces): the widest is Norse's "Profile and account", 166.9 at 17, so 22 draws it
     *  216.0 wide in the 220 of room (23 would be 225.8). iOS's cap is the same 22 (TitleStyle.inlineCap; fix wave,
     *  decision 202: one cap, the twins identical). At the phone's own size (17) and up to step +2 (21.1) it does not bite. */
    const val INLINE_TITLE_CAP = 22

    /** The room a pushed title has on a 360 dp phone: the bar inside its side margins, less the back button's node
     *  ([controlShift] and the 48 dp target) on both sides (the title is centred on the bar) and [TITLE_GAP] each side. */
    val INLINE_TITLE_ROOM_360: Int
        get() = 360 - 2 * BAR_SIDE_MARGIN - 2 * (controlShift(BACK_CENTRE_FROM_EDGE) + TOUCH_TARGET) - 2 * TITLE_GAP

    /** An inline title's size in dp: the scaled 17 ([scaledDp], the font scale's), at most [INLINE_TITLE_CAP]. */
    fun inlineTitleDp(scaledDp: Float): Float = minOf(scaledDp, INLINE_TITLE_CAP.toFloat())

    /** Whether the bar's own centred title shows. A tab root shows it once the large title's bottom is under the
     *  bar: [largeTitleBottomPx] is that bottom measured from the bar's lower edge with the content unscrolled, so
     *  the title has gone under it when the content has scrolled that far. A pushed or sheet bar always shows it. */
    fun inlineTitleShown(form: BarForm, scrollPx: Int, largeTitleBottomPx: Int): Boolean =
        if (form == BarForm.TAB_ROOT) scrollPx >= largeTitleBottomPx else true

    /** A sheet whose title is LARGE (Add and Edit expense: the one sheet root iOS sets large) folds its title into the
     *  bar exactly as a tab root's does; every other sheet's inline title is always there. */
    fun largeSheetInlineTitleShown(scrollPx: Int, largeTitleBottomPx: Int): Boolean =
        inlineTitleShown(BarForm.TAB_ROOT, scrollPx, largeTitleBottomPx)

    /** The bar's ground: clear at the top (iOS's scroll-edge appearance), the theme's bar ground once the content
     *  has scrolled (its standard appearance). */
    fun barGroundShown(scrollPx: Int): Boolean = scrollPx > 0

    /** A large title's bottom, px from the top of the scrolling content at scroll 0: its place in the column (which
     *  reads inside the column's padding, 0 for the first child, measured on the emulator), its height, and the top
     *  padding. The tab root and the large sheet both fold their title by it. */
    fun largeTitleBottomPx(positionY: Int, heightPx: Int, topPadPx: Int): Int = positionY + heightPx + topPadPx

    /** D-234 (L4, decision 92): where a large-title page comes to rest, as UIKit's large title does: a scroll that
     *  stopped with the title partly under the bar (between 0 and [foldPx], the scroll at which its bottom reaches the
     *  bar's) goes back to 0 when less than half way, else on to [foldPx]; anywhere else, or before the title is
     *  measured ([foldPx] 0), it stays. */
    fun largeTitleRest(scrollPx: Int, foldPx: Int): Int = when {
        foldPx <= 0 || scrollPx <= 0 || scrollPx >= foldPx -> scrollPx
        scrollPx * 2 < foldPx -> 0
        else -> foldPx
    }

    /** D-234 (L4): the room a large-title page adds under its content so it can scroll its title fully under the bar
     *  (UIKit's collapsing bar frees that height): what the content's own maximum scroll lacks of [foldPx]. None when
     *  the content does not scroll at all (UIKit leaves such a page at rest too) or is not measured yet. */
    fun largeTitleSlackPx(maxScrollPx: Int, foldPx: Int): Int = if (foldPx > 0 && maxScrollPx in 1 until foldPx) foldPx - maxScrollPx else 0

    /** The bar's title is a heading only when it is one and says something (an empty heading is still announced). */
    fun titleIsHeading(heading: Boolean, title: String): Boolean = heading && title.isNotEmpty()

    /** A lazy list reports its scroll as the first visible item and the offset into it; the ground needs only
     *  whether it has moved at all, so any item past the first counts as at least one pixel. */
    fun lazyScrollPx(firstVisibleIndex: Int, firstVisibleOffsetPx: Int): Int =
        if (firstVisibleIndex > 0) maxOf(1, firstVisibleOffsetPx) else firstVisibleOffsetPx

    /** The bar's ground reaches up under the status bar (iOS's does), so a scrolled bar has no seam against the
     *  strip above it: it starts [statusTopPx] above the bar's top edge... */
    fun groundTop(statusTopPx: Int): Int = -statusTopPx

    /** D-217 final fix: the inset the ground reaches up by. A bar drawn inside a bottom sheet or a dialog window is
     *  not under the status bar (the window's inset can still read non-zero there), so it reaches up by nothing. */
    fun statusTopPx(underStatusBar: Boolean, insetPx: Int): Int = if (underStatusBar) insetPx else 0

    /** ...and runs down to the bar's bottom, so its height is the status bar's plus the bar's. */
    fun groundHeight(statusTopPx: Int, barPx: Int): Int = statusTopPx + barPx

    /** The gap between the centred title and a control, dp. */
    const val TITLE_GAP = 8
    /** A sheet pill (Cancel, Done, Close), D-217 look fix 3: iOS's glass pill is 44 pt tall at the default text size
     *  and 16 pt wider each side than its label, and it GROWS with the label (the label is never clipped). Its height
     *  is a floor, [PILL_HEIGHT], and the label's line plus [PILL_VERTICAL_PADDING] each side once that is taller; it
     *  starts at the bar's top (44 dp, the bar's own height) inside its 48 dp target. */
    const val PILL_HEIGHT = 44
    const val PILL_SIDE_PADDING = 16
    const val PILL_VERTICAL_PADDING = 10

    /** The pill's own inset in its 48 dp target, each side, dp. */
    const val PILL_TARGET_INSET = 2

    /** D-248 fix wave (review I3): the largest a pill's label is drawn, dp. iOS 26 and later draw a bar button's text
     *  at 20 at most whatever it is given (measured on iOS 27: Cancel at 20 from XXL up to AX5; iOS's
     *  `TitleStyle.barButtonCap` is the same 20), so a sheet's title keeps its room beside the pill at any font scale
     *  (D248FixesTest's sheet table). Uncapped, "Cancel" at the 2.0 scale is 103 dp wide and leaves a Norse "PICK YOUR
     *  USERNAME" no room. */
    const val PILL_TEXT_CAP = 20f

    /** A pill label's size in dp: the scaled 17 ([scaledDp], the font scale's), at most [PILL_TEXT_CAP]. */
    fun pillTextDp(scaledDp: Float): Float = minOf(scaledDp, PILL_TEXT_CAP)

    /** A pill's whole node, dp, for a label [labelDp] wide: the label, [PILL_SIDE_PADDING] each side, and its inset in
     *  the target. */
    fun pillNodeWidth(labelDp: Float): Float = labelDp + 2 * PILL_SIDE_PADDING + 2 * PILL_TARGET_INSET

    /** D-248 fix wave (review I3): the room a SHEET's title has on a 360 dp phone beside a pill whose label is
     *  [labelDp] wide ([titleLeft]'s rule: clear of the pill by [TITLE_GAP], and [TITLE_GAP] from the bar's far margin). */
    fun sheetTitleRoom360(labelDp: Float): Float =
        360f - 2 * BAR_SIDE_MARGIN - pillNodeWidth(labelDp) - 2 * TITLE_GAP

    /** The pill's height in dp for a label line [lineDp] tall: the floor, or the line and its padding. */
    fun pillHeight(lineDp: Float): Float = maxOf(PILL_HEIGHT.toFloat(), lineDp + 2 * PILL_VERTICAL_PADDING)

    /** The row's node: at least the 48 dp target, and tall enough for the tallest control (a pill at a large text
     *  size is taller than 48). */
    fun nodeHeight(minPx: Int, startPx: Int, endPx: Int): Int = maxOf(minPx, startPx, endPx)

    /** D-217 final fix, D-248 fix wave (review I3): the widest the title may be: the room between the two measured
     *  controls, less a gap beside each (a Cancel pill at a large text size is far wider than any fixed reserve). Never
     *  negative. D-217 kept the title clear of the WIDER control on both sides; UIKit does not (measured on iOS 27,
     *  D248FixesTests' real-bar table: a sheet title too wide to centre starts 12 pt after the Cancel pill and runs to
     *  16 pt from the other side, whole), so that rule cut titles iOS shows whole. */
    fun titleMaxWidth(width: Int, leading: Int, trailing: Int, gap: Int): Int =
        maxOf(0, width - leading - trailing - 2 * gap)

    /** Where a control or the title starts down the bar: one shorter than the bar is centred on it; a 48 dp target
     *  (taller than the bar) sits at its top and centres its own drawing on the bar. */
    fun centredTop(height: Int, barPx: Int): Int = if (height >= barPx) 0 else (barPx - height) / 2

    /** D-217 look fix 3: the height controls and the title are centred in. The bar's own, except when a control is
     *  taller than the 48 dp target (a pill at a large text size): then the bar grows to it, so the pill, the other
     *  control and the title all centre on the same line. At the default size nothing is taller, so nothing moves. */
    fun laneHeight(tallestPx: Int, barPx: Int, targetPx: Int): Int = if (tallestPx > targetPx) tallestPx else barPx

    /** The title's start: centred on the bar (so on the screen) when that clears both controls by [gap]; else moved
     *  just clear of the control it would run into (UIKit's rule, measured). */
    fun titleLeft(width: Int, titleWidth: Int, leading: Int, trailing: Int, gap: Int): Int {
        val least = leading + gap
        val most = maxOf(least, width - trailing - gap - titleWidth)
        return ((width - titleWidth) / 2).coerceIn(least, most)
    }
}

private val BarSideMargin = BarDecisions.BAR_SIDE_MARGIN.dp

/** The ground's height in px for a bar node of [size]: the bar's own, or the node's once a pill outgrows the 48 dp
 *  target. */
private fun Density.groundPx(size: Size): Int {
    val barPx = BarDecisions.BAR_HEIGHT.dp.toPx()
    val groundPx = BarDecisions.laneHeight(size.height.toInt(), barPx.toInt(), BarDecisions.TOUCH_TARGET.dp.roundToPx())
    return groundPx
}

/** The height a [fillRemainingHeight] state takes, in px (see [BarDecisions.fillRemainingPx]); -1 while unmeasured. */
private val LocalTabRootFillPx = compositionLocalOf { 0 }

/** A tab root's state (loading, error, empty) that fills the free space under the title and centres its own content:
 *  use it on the state's box, whose `contentAlignment` is Center. Hidden for the one frame before the space is
 *  measured. Only meaningful inside [TabRootScaffold]. */
@Composable
fun Modifier.fillRemainingHeight(): Modifier {
    val px = LocalTabRootFillPx.current
    val height = with(LocalDensity.current) { px.coerceAtLeast(0).toDp() }
    return this.fillMaxWidth().heightIn(min = height).graphicsLayer { alpha = if (px >= 0) 1f else 0f }
}

/** The bar's ground and the hairline under it, faded in once scrolled. D-220 (L-D217-3): the ground is the tab bar's
 *  decision, `TabBarGround`: Norse and Vardr Labs the solid surface; Aurora glass, the page under the bar blurred
 *  ([backdrop], API 31+) under the surface at `TabBarGround.film`, or the film alone where it cannot blur. */
@Composable
private fun BarFrame(
    dark: Boolean,
    groundOn: Boolean,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    underStatusBar: Boolean = true,
    backdrop: Backdrop? = null,
    title: @Composable () -> Unit,
) {
    val reduce = rememberMotionState().reduce
    val ground by animateFloatAsState(if (groundOn) 1f else 0f, if (reduce) snap() else tween(150), label = "barGround")
    val theme = Appearance.theme
    val blur = backdrop?.content != null && TabBarGround.of(theme) == TabBarGround.GLASS
    val film = if (BarDecisions.navFilmIsWhite(theme, blur)) {
        Color.White.copy(alpha = BarDecisions.navFilmWhite(dark))
    } else {
        Tok.surface(dark).copy(alpha = TabBarGround.film(theme, blur))
    }
    val hairline = Tok.hairline(dark)
    // The raw status bar inset (the shell's one safeDrawing padding consumes it for layout, not for this read); none
    // for a bar in a sheet or dialog window.
    val statusTop = BarDecisions.statusTopPx(underStatusBar, WindowInsets.statusBars.getTop(LocalDensity.current))
    // The bar takes 44 dp in the layout, but its own node is 48 dp tall, flush with the bar's top and overflowing 4 dp
    // below it: a child is only hit inside its parent's bounds, and the bar's top edge is the window's inset edge (the
    // shell's safeDrawing padding), so the extra 4 dp can only go below. The controls ([start], [end]: the back
    // button's, the glyph's and the pills' 48 x 48 targets) and the title are laid out in that node by [BarRow]. The
    // ground and the hairline are drawn for the 44 dp.
    // TalkBack: the content fills the page from its top, so by position it would be read before the bar over it; the
    // bar is a traversal group of its own, ordered first.
    Box(
        Modifier
            .fillMaxWidth()
            .height(BarDecisions.BAR_HEIGHT.dp)
            .semantics {
                isTraversalGroup = true
                traversalIndex = BarDecisions.BAR_TRAVERSAL_INDEX
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .heightIn(min = BarDecisions.TOUCH_TARGET.dp)
                // The ground reaches up under the status bar and down to the bar's bottom (the node's, once a pill has
                // outgrown the 48 dp target); the page is cut out from under it while it is on.
                .backdropGlass(
                    backdrop, film, blur,
                    alpha = { ground },
                    active = { groundOn },
                    rect = { s ->
                        val groundPx = groundPx(s)
                        val top = BarDecisions.groundTop(statusTop).toFloat()
                        Rect(Offset(0f, top), Size(s.width, BarDecisions.groundHeight(statusTop, groundPx).toFloat()))
                    },
                    cap = BarDecisions.navBandCap(theme, dark, blur),
                )
                .drawBehind {
                    val groundPx = groundPx(size)
                    val line = groundPx - 0.5.dp.toPx()
                    drawLine(hairline.copy(alpha = ground), Offset(0f, line), Offset(size.width, line), strokeWidth = 1.dp.toPx())
                }
                .padding(horizontal = BarSideMargin),
        ) {
            BarRow(start, end, title)
        }
    }
}

/** D-217 final fix: the bar's row, measured: the controls first, then the title at most as wide as
 *  [BarDecisions.titleMaxWidth] allows, centred on the bar where it clears both controls and moved clear of one where
 *  it does not ([BarDecisions.titleLeft], D-248 fix wave), so it never draws under a control however wide the text
 *  size makes it. Placed relative to the layout direction (the start control is on the right in a right-to-left
 *  language). */
@Composable
private fun BarRow(start: (@Composable () -> Unit)?, end: (@Composable () -> Unit)?, title: @Composable () -> Unit) {
    Layout(
        contents = listOf(start ?: {}, end ?: {}, title),
        modifier = Modifier.fillMaxWidth(),
    ) { (startMeasurables, endMeasurables, titleMeasurables), constraints ->
        val width = constraints.maxWidth
        val barPx = BarDecisions.BAR_HEIGHT.dp.roundToPx()
        // The controls are measured with no height limit: a pill at a large text size is taller than the 48 dp node.
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val s = startMeasurables.firstOrNull()?.measure(loose)
        val e = endMeasurables.firstOrNull()?.measure(loose)
        val gap = BarDecisions.TITLE_GAP.dp.roundToPx()
        val maxTitle = BarDecisions.titleMaxWidth(width, s?.width ?: 0, e?.width ?: 0, gap)
        val t = titleMeasurables.firstOrNull()?.measure(Constraints(maxWidth = maxTitle))
        val lane = BarDecisions.laneHeight(maxOf(s?.height ?: 0, e?.height ?: 0), barPx, BarDecisions.TOUCH_TARGET.dp.roundToPx())
        layout(width, BarDecisions.nodeHeight(BarDecisions.TOUCH_TARGET.dp.roundToPx(), s?.height ?: 0, e?.height ?: 0)) {
            s?.placeRelative(0, BarDecisions.centredTop(s.height, lane))
            e?.placeRelative(width - e.width, BarDecisions.centredTop(e.height, lane))
            t?.placeRelative(BarDecisions.titleLeft(width, t.width, s?.width ?: 0, e?.width ?: 0, gap), BarDecisions.centredTop(t.height, lane))
        }
    }
}

/** The centred 17 title: one line, ellipsis, as wide as [BarRow] lets it be. [alpha] is the tab root's crossfade. */
@Composable
private fun InlineTitle(title: String, form: BarForm, dark: Boolean, alpha: Float, heading: Boolean) {
    // D-248 (U31): the 17 scaled by the font scale (nonlinear on Android 14 and later), stopped at the cap.
    val size = with(LocalDensity.current) {
        BarDecisions.inlineTitleDp(BarDecisions.titleSize(form, collapsed = true).sp.toDp().value).dp.toSp().value
    }
    Text(
        title,
        style = Type.title(dark, size),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .then(if (BarDecisions.titleIsHeading(heading, title)) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}),
    )
}

/**
 * The tab root: the large title is the first child of the scrolling content (with the Norse ornament under it when
 * [ornament]); the bar sits over the content at the top, clear while the content is at the top, and its centred
 * inline title fades in once the large title's bottom has gone under it. [trailing] is the bar's right end (the
 * account glyph): a 48 dp square item, top-right in the bar's 48 dp node, that centres its own drawing on the 44 dp
 * bar.
 *
 * The four tab roots (Balance, Groups, Expenses, Settle up) are all one Column in a vertical scroll on
 * [rememberShellScrollState], so that is the content model: a [ColumnScope] with the same 16 dp sides and 14 dp
 * spacing they have today, and [scroll] to hand in a state of the caller's own. A child that should fill what is left
 * under the title (a centred empty state) takes [fillRemainingHeight].
 */
@Composable
fun TabRootScaffold(
    title: String,
    ornament: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    scroll: ScrollState = rememberShellScrollState(),
    iosPadding: Int = BarDecisions.TAB_ROOT_BASE_PADDING,
    /** D-223 fix wave (F5): the iOS twin's stack spacing under its Norse ornament (Balance's empty state: 12). */
    iosOrnamentSpacing: Int = BarDecisions.CONTENT_SPACING,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    // D-220 (L-D217-3): the page records into the shell's backdrop (the tab bar's glass reads it too), or its own outside
    // the shell; it runs under the shell's tab bar and keeps the bar's height clear at its end.
    val ownBackdrop = rememberBackdrop()
    val backdrop = LocalShellBackdrop.current ?: ownBackdrop
    val shellInset = LocalShellBarInset.current
    // D-217 look fix 1: the first content's gap under the title is iOS's ScrollView padding on top of what the Aurora
    // sets have in common: Balance, Expenses and Settle up pad 16 and Groups 8.
    // D-223 fix wave (F5): in Norse, the content hands over from the ornament as iOS's does (TitleOrnaments.contentShift).
    val extraTop = BarDecisions.tabRootExtra(iosPadding) + (if (ornament && TitleOrnaments.shows(Appearance.theme)) TitleOrnaments.contentShift(iosPadding, iosOrnamentSpacing) else 0)
    val extraTopPx = with(LocalDensity.current) { extraTop.dp.roundToPx() }
    val barPx = with(LocalDensity.current) { BarDecisions.BAR_HEIGHT.dp.roundToPx() }
    // The large title's bottom in the scrolling content: positionInParent is inside the Column's padding (measured
    // on the emulator: it reads 0 for the first child), so the top padding, a bar's height and 8 dp, is added; less the
    // bar's height, that is its distance below the bar's lower edge at scroll 0.
    val topPadPx = with(LocalDensity.current) { BarDecisions.CONTENT_TOP_PAD.dp.roundToPx() }
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // How far the large title's box ends under its baseline, dp: the ornament hangs 8 dp under the BASELINE (iOS's
    // measurement was on the glyph bottom), so it cancels this as well as the column's spacing.
    var belowBaseline by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // The viewport's height and where the content starts in the column (below the title and the ornament), for the
    // states that fill the rest.
    var viewportPx by remember { mutableIntStateOf(0) }
    var contentTop by remember { mutableIntStateOf(-1) }
    val bottomPadPx = with(LocalDensity.current) { (BarDecisions.CONTENT_BOTTOM_PAD.dp + shellInset).roundToPx() }
    val fillPx = BarDecisions.fillRemainingPx(viewportPx, topPadPx, contentTop, bottomPadPx)
    Box(Modifier.fillMaxSize().onSizeChanged { viewportPx = it.height }) {
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = BarDecisions.CONTENT_TOP_PAD.dp, bottom = BarDecisions.CONTENT_BOTTOM_PAD.dp + shellInset),
            verticalArrangement = Arrangement.spacedBy(BarDecisions.CONTENT_SPACING.dp),
        ) {
            Text(
                title,
                style = Type.title(dark, BarDecisions.titleSize(BarForm.TAB_ROOT, collapsed = false)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    belowBaseline = with(density) { (layout.size.height - layout.getLineBaseline(0)).toDp().value.roundToInt() }
                },
                modifier = Modifier
                    .semantics { heading() }
                    .onGloballyPositioned { titleBottom = BarDecisions.largeTitleBottomPx(it.positionInParent().y.roundToInt(), it.size.height, topPadPx) },
            )
            if (ornament) TitleOrnament(dark, above = BarDecisions.CONTENT_SPACING, belowBaseline = belowBaseline)
            CompositionLocalProvider(LocalTabRootFillPx provides fillPx) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { contentTop = it.positionInParent().y.roundToInt() + extraTopPx }
                        .padding(top = extraTop.dp),
                    verticalArrangement = Arrangement.spacedBy(BarDecisions.CONTENT_SPACING.dp),
                ) { content() }
            }
        }
        TabRootBar(title, dark, scroll, barPx, { titleBottom }, trailing, backdrop)
    }
}

/** Its own composable so a scroll frame recomposes only the bar, and only when a threshold is crossed. */
@Composable
private fun TabRootBar(
    title: String,
    dark: Boolean,
    scroll: ScrollState,
    barPx: Int,
    titleBottom: () -> Int,
    trailing: (@Composable () -> Unit)?,
    backdrop: Backdrop,
) {
    val inlineOn by remember(scroll, barPx) {
        derivedStateOf {
            val bottom = titleBottom()
            bottom != Int.MAX_VALUE && BarDecisions.inlineTitleShown(BarForm.TAB_ROOT, scroll.value, bottom - barPx)
        }
    }
    val groundOn by remember(scroll) { derivedStateOf { BarDecisions.barGroundShown(scroll.value) } }
    val reduce = rememberMotionState().reduce
    val alpha by animateFloatAsState(if (inlineOn) 1f else 0f, if (reduce) snap() else tween(150), label = "inlineTitle")
    // The trailing item sits in the bar's 48 dp node at its end, like the back button at the start: it is a full touch
    // target, so the item is itself 48 dp square and centres its own drawing on the 44 dp bar (22 dp down).
    BarFrame(dark, groundOn, end = trailing, backdrop = backdrop) {
        InlineTitle(title, BarForm.TAB_ROOT, dark, alpha, heading = false)
    }
}

/** A pushed page's bar: the round back button (Lucide chevron-left, drawn alone: iOS 26 and 27 show no parent title
 *  beside it) and the title centred. [scroll] is the page's scroll state (or [lazy], a lazy list's), for the ground:
 *  it is read only inside a derived state here, so a scroll frame does not recompose the caller. System back stays
 *  with the caller's BackHandler: the button only calls [onBack]. [underStatusBar] is false for a bar in a dialog window
 *  (the currency list), whose ground must not reach up by the status bar. A null [onBack] draws no button (iOS's signed-in
 *  confirmation hides its back button and keeps the title). D-220: [backdrop] is the page's (its scrolling content
 *  takes [backdropSource] with it), so Aurora's ground blurs it; without one the bar draws the film alone. */
@Composable
fun PushedBar(
    title: String,
    onBack: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
    scroll: ScrollState? = null,
    lazy: LazyListState? = null,
    underStatusBar: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val dark = isSystemInDarkTheme()
    val groundOn by remember(scroll, lazy) {
        derivedStateOf {
            val px = scroll?.value ?: lazy?.let { BarDecisions.lazyScrollPx(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) } ?: 0
            BarDecisions.barGroundShown(px)
        }
    }
    BarFrame(
        dark, groundOn, underStatusBar = underStatusBar,
        start = if (onBack != null) ({ BackButton(dark, onBack) }) else null,
        end = trailing,
        backdrop = backdrop,
    ) {
        InlineTitle(title, BarForm.PUSHED, dark, 1f, heading = true)
    }
}

/**
 * D-217 Task 7: a pushed page, whole: the Aurora ground, the scrolling column that starts under the bar, and the
 * [PushedBar] over it. Every pushed screen that scrolled a [Column] (the ones that had a hand-built header before) is
 * this, so the title is one decision and the content's first row is one bar and 8 dp (CONTENT_TOP_PAD) below the top.
 * [spacing] is the gap between the column's children; the content is a [ColumnScope], so a screen leaves early with
 * `return@PushedPage`. [side] is the side padding (iOS's twin's: [BarDecisions.PAGE_SIDE] or [BarDecisions.PAGE_SIDE_WIDE]);
 * [top] is where the content starts under the bar (D-217 look fix 1): by default a ScrollView's, the iOS padding on top of
 * the bar's clearance ([BarDecisions.scrollTop]); a page whose iOS twin is a Form passes [BarDecisions.formTop].
 */
@Composable
fun PushedPage(
    title: String,
    dark: Boolean,
    onBack: (() -> Unit)?,
    spacing: Dp = 0.dp,
    side: Dp = BarDecisions.PAGE_SIDE.dp,
    top: Dp = BarDecisions.scrollTop(side.value).dp,
    trailing: (@Composable () -> Unit)? = null,
    // D-225 fix wave (F6): the page's own scroll state unless the caller keeps one for it (You keeps its offset while
    // its pages are pushed, and each page opens at its top).
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(scroll)
                .padding(start = side, end = side, top = top, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        PushedBar(title, onBack, trailing, scroll, backdrop = backdrop)
    }
}

/** The 44 dp circle, the chevron in the label colour on the surface token, a 48 dp touch target, spoken as "Back".
 *  D-220 fix wave (F4): the target sits in by [BarDecisions.controlShift], so the circle's centre is iOS's 38 dp from
 *  the edge. */
@Composable
private fun BackButton(dark: Boolean, onBack: () -> Unit) {
    Box(
        Modifier
            .padding(start = BarDecisions.controlShift(BarDecisions.BACK_CENTRE_FROM_EDGE).dp)
            .size(BarDecisions.TOUCH_TARGET.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = (BarDecisions.BACK_BUTTON_SIZE / 2).dp),
                role = Role.Button,
                onClick = onBack,
            )
            .semantics { contentDescription = BarDecisions.BACK_LABEL },
        // The circle is centred on the 44 dp bar (22 dp down) in its 48 dp target.
        contentAlignment = Alignment.TopCenter,
    ) {
        BarCircle(dark, BarDecisions.BACK_BUTTON_SIZE) {
            // D-217 final fix: the label colour (the title's), as iOS 26 and 27 draw the chevron in its glass.
            Icon(TabIcons.ChevronLeft, contentDescription = null, tint = Tok.text(dark), modifier = Modifier.size(BarDecisions.backChevronDp().dp))
        }
    }
}

/** D-217 look fix 3: the bar's round control, the twin of iOS 26's glass circle: [sizeDp] tall (the back button's: 45),
 *  [width] across (a capsule when wider: the account glyph round a badge, D-220 A1), centred on the 44 dp bar. Place
 *  it at the top of its 48 dp target. D-220: iOS's glass, white at [BarDecisions.glassFillAlpha] over what is under
 *  it, with a 1 dp hairline ring (iOS 26's glass has a rim; on a scrolled surface bar a surface disc vanished). */
@Composable
internal fun BarCircle(dark: Boolean, sizeDp: Int, width: Int = sizeDp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .offset(y = BarDecisions.roundTop(sizeDp).dp)
            // Required: the 51 dp capsule is wider than its 48 dp target, and overflows it evenly.
            .requiredSize(width = width.dp, height = sizeDp.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = BarDecisions.glassFillAlpha(dark)))
            .border(1.dp, Tok.hairline(dark), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A sheet's bar: "Cancel" (or [cancelLabel]: iOS's Add a person puts "Done" in that corner) and (when [onDone] is given)
 *  "Done" as pills in the corners, the title centred. Norse notches the pills as its other controls (D-215 G1); the
 *  others keep their own control shape. [largeTitleBottom] is set when the page draws its own LARGE title (Add and
 *  Edit expense): the bar's centred title then waits until that title has folded under it. [onSurface] is for a bar
 *  drawn on a sheet whose own ground is the surface token (the person sheet), where a surface pill would vanish.
 *  [underStatusBar] is false for a bar in a bottom sheet or dialog window, which is not under the status bar: its
 *  ground must not reach up by the inset (D-217 look fix 5: its own flag, it was folded into [onSurface]). */
@Composable
fun SheetBar(
    title: String,
    onCancel: () -> Unit,
    // A sheet's Done pill: no screen has one yet (iOS's sheets put Done in their pinned action), the `--bar-proof`
    // look draws it; kept so the sheet bar is iOS's whole bar.
    onDone: (() -> Unit)? = null,
    doneEnabled: Boolean = true,
    scroll: ScrollState? = null,
    cancelLabel: String = BarDecisions.CANCEL_LABEL,
    largeTitleBottom: (() -> Int)? = null,
    onSurface: Boolean = false,
    underStatusBar: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val dark = isSystemInDarkTheme()
    val barPx = with(LocalDensity.current) { BarDecisions.BAR_HEIGHT.dp.roundToPx() }
    val groundOn by remember(scroll) { derivedStateOf { BarDecisions.barGroundShown(scroll?.value ?: 0) } }
    val inlineOn by remember(scroll, largeTitleBottom) {
        derivedStateOf {
            if (largeTitleBottom == null) true
            else {
                val bottom = largeTitleBottom()
                bottom != Int.MAX_VALUE && BarDecisions.largeSheetInlineTitleShown(scroll?.value ?: 0, bottom - barPx)
            }
        }
    }
    val reduce = rememberMotionState().reduce
    val alpha by animateFloatAsState(if (inlineOn) 1f else 0f, if (reduce) snap() else tween(150), label = "sheetInlineTitle")
    BarFrame(
        dark, groundOn, underStatusBar = underStatusBar,
        start = { BarPill(cancelLabel, dark, true, onSurface, onCancel) },
        end = if (onDone != null) ({ BarPill(BarDecisions.DONE_LABEL, dark, doneEnabled, onSurface, onDone) }) else null,
        backdrop = backdrop,
    ) {
        InlineTitle(title, BarForm.SHEET, dark, alpha, heading = largeTitleBottom == null)
    }
}

/**
 * D-217 Task 8: a sheet's page, whole: the Aurora ground, the scrolling column that starts under the bar, the bar over
 * it ([SheetBar] with Cancel; with a null [onCancel] a [PushedBar] with no button, for a form that has no way out),
 * and, when [actions] is given, the screen's main action pinned under the content as iOS's `formActionBar` does.
 * [largeTitle] draws the 34 left title as the first child (iOS sets it large on Add and Edit expense alone), and the
 * bar's centred title folds in as it scrolls under. [cancelLabel] renames the left pill ("Done" on Add a person).
 * [top] is where the content starts under the bar (D-217 look fix 1): a large-title sheet's own, else a ScrollView's
 * ([BarDecisions.scrollTop]); a sheet whose iOS twin is a Form passes [BarDecisions.formTop].
 */
@Composable
fun SheetPage(
    title: String,
    dark: Boolean,
    onCancel: (() -> Unit)?,
    spacing: Dp = 0.dp,
    side: Dp = BarDecisions.PAGE_SIDE.dp,
    cancelLabel: String = BarDecisions.CANCEL_LABEL,
    largeTitle: Boolean = false,
    top: Dp = (if (largeTitle) BarDecisions.LARGE_SHEET_TOP else BarDecisions.scrollTop(side.value)).dp,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val topPadPx = with(LocalDensity.current) { top.roundToPx() }
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // D-234 (L4, decision 92): a large title's page rests as UIKit's does: never half under the bar once a finger lets
    // go, and always able to scroll its title fully under it (the room a short form lacks goes under the content).
    val barPx = with(LocalDensity.current) { BarDecisions.BAR_HEIGHT.dp.roundToPx() }
    val reduce = rememberMotionState().reduce
    val foldPx = if (largeTitle && titleBottom != Int.MAX_VALUE) titleBottom - barPx else 0
    var viewportPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(scroll, foldPx, reduce) {
        if (foldPx <= 0) return@LaunchedEffect
        // Only a scroll a finger started comes to rest (UIKit adjusts where a drag's deceleration ends); a scroll the
        // page makes itself (a focused field brought into view) stays where it put the page.
        var dragged = false
        launch { scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving && dragged) {
                val rest = BarDecisions.largeTitleRest(scroll.value, foldPx)
                try {
                    if (rest != scroll.value) {
                        if (reduce) scroll.scrollTo(rest) else scroll.animateScrollTo(rest)
                    }
                    dragged = false
                } catch (e: CancellationException) {
                    // A fling or a new drag took the page first: it rests when that stops. Our own end still ends us.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }
    // One lambda for the page's life (it reads the state when called), so the bar's derived state, keyed on it, is not
    // made again on every recomposition (every keystroke on Add expense).
    val largeBottom = remember { { titleBottom } }
    val backdrop = rememberBackdrop()
    Column(Modifier.fillMaxSize().auroraBackground(dark)) {
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportPx = it.height }
                    .backdropSource(backdrop)
                    .verticalScroll(scroll)
                    // D-234 (L4): the room a short large-title form lacks, measured in layout from the content alone
                    // (so it never feeds back into itself); 0 on every sheet without a large title.
                    .layout { measurable, constraints ->
                        val content = measurable.measure(constraints)
                        val slack = BarDecisions.largeTitleSlackPx(content.height - viewportPx, foldPx)
                        layout(content.width, content.height + slack) { content.place(0, 0) }
                    }
                    .padding(start = side, end = side, top = top, bottom = if (actions != null) 8.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                if (largeTitle) {
                    Text(
                        title,
                        style = Type.title(dark, BarDecisions.titleSize(BarForm.TAB_ROOT, collapsed = false)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .semantics { heading() }
                            .onGloballyPositioned { titleBottom = BarDecisions.largeTitleBottomPx(it.positionInParent().y.roundToInt(), it.size.height, topPadPx) },
                    )
                }
                content()
            }
            if (onCancel != null) {
                SheetBar(title, onCancel, scroll = scroll, cancelLabel = cancelLabel, largeTitleBottom = if (largeTitle) largeBottom else null, backdrop = backdrop)
            } else if (!largeTitle) {
                // No way out and no large title: the title is the bar's. (A large title with no Cancel, the `--add`
                // proof, draws no bar: its own title is the page's.)
                PushedBar(title, onBack = null, scroll = scroll, backdrop = backdrop)
            }
        }
        if (actions != null) FormActionBar(actions)
    }
}

/** The screen's main action in a bar under the form (iOS's `formActionBar`: 20 pt sides, 8 above and below). D-220
 *  (L-D217-4): the actions are backed with the page colour in the control shape, as iOS's are, so a dimmed button
 *  never shows the art or a row through it. */
@Composable
fun FormActionBar(content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .background(Tok.bg(dark), ThemeArt.controlShape(Appearance.theme)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A pill: its touch target is the bar's 48 dp node (as the back button's), the pill itself at its top, [BarDecisions.PILL_HEIGHT]
 *  tall at least and as tall as its label and padding make it at a large text size (iOS's glass pill grows with the
 *  text), 16 dp wider each side than the label. */
@Composable
private fun BarPill(label: String, dark: Boolean, enabled: Boolean, onSurface: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = BarDecisions.TOUCH_TARGET.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = BarDecisions.PILL_TARGET_INSET.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .heightIn(min = BarDecisions.PILL_HEIGHT.dp)
                .clip(ThemeArt.controlShape(Appearance.theme))
                .background(if (onSurface) Tok.surfaceRaised(dark) else Tok.surface(dark))
                // D-220: a 1 dp hairline ring (iOS 26's glass pill has a rim).
                .border(1.dp, Tok.hairline(dark), ThemeArt.controlShape(Appearance.theme))
                .padding(horizontal = BarDecisions.PILL_SIDE_PADDING.dp, vertical = BarDecisions.PILL_VERTICAL_PADDING.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
            contentAlignment = Alignment.Center,
        ) {
            // D-217 final fix: the bar tint (iOS `.barTint()`, BarTint.token), not the accent: they differ in Aurora light.
            // D-248 fix wave (review I3): the 17 scaled by the font scale, stopped at the pill's cap (iOS's bar buttons stop
            // at the same 20).
            val size = with(LocalDensity.current) { BarDecisions.pillTextDp(BarDecisions.BAR_BUTTON_SIZE.sp.toDp().value).dp.toSp() }
            Text(label, style = Type.body(dark).copy(fontSize = size, color = Tok.groundAccent(dark)), maxLines = 1)
        }
    }
}

/**
 * `--bar-proof [tab|pushed|sheet]` (Debug builds only, a look's tool, not a user flow): each form over rows enough to
 * scroll, in whichever theme and mode the phone is set to.
 */
@Composable
internal fun BarProof(form: String?) {
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        when (form) {
            "pushed" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                var taps by remember { mutableIntStateOf(0) }
                PushedBar("Back taps $taps", onBack = { taps++ }, scroll = scroll)
            }
            "pushed-long" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                PushedBar("A long group name that has to stay clear of both buttons", onBack = {}, scroll = scroll)
            }
            "sheet" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                SheetBar("Add expense", onCancel = {}, onDone = {}, scroll = scroll)
            }
            "sheet-long" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                SheetBar("A long group name that has to stay clear of both buttons", onCancel = {}, onDone = {}, scroll = scroll)
            }
            else -> TabRootScaffold(
                title = "Balance",
                ornament = true,
                trailing = {
                    Box(Modifier.size(BarDecisions.TOUCH_TARGET.dp), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.height(BarDecisions.BAR_HEIGHT.dp), contentAlignment = Alignment.Center) {
                            Icon(TabIcons.UserRound, contentDescription = "Settings", tint = Tok.textDim(dark), modifier = Modifier.size(28.dp))
                        }
                    }
                },
            ) {
                repeat(14) { BarProofRow(it + 1, dark) }
            }
        }
    }
}

@Composable
private fun BarProofRows(scroll: ScrollState) {
    val dark = isSystemInDarkTheme()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, top = BarDecisions.CONTENT_TOP_PAD.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(14) { BarProofRow(it + 1, dark) }
    }
}

@Composable
private fun BarProofRow(n: Int, dark: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(ThemeArt.panelShape(Appearance.theme, 14))
            .background(Tok.surface(dark))
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Row $n", style = Type.body(dark))
    }
}
