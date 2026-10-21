package com.vardrlabs.skipti

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * D-220 (L-D217-3): Android's bar glass, our own code on platform APIs (no dependency). iOS's Aurora bars are a
 * material that blurs what scrolls under them; Android had only a film of the surface.
 *
 * A page's scrolling content is the SOURCE ([backdropSource]): it is recorded into a [GraphicsLayer] and drawn as
 * usual, except under every bar whose ground is on (cut out, so nothing sharp shows through the film). A bar is a
 * PANE ([backdropGlass]): it draws the source's layer again, offset to its own rect, through a second layer with
 * [BlurEffect] ([TabBarGround.BLUR_RADIUS]), then its film ([TabBarGround.film]). RenderEffect exists from API 31
 * ([TabBarGround.blurs]); below it the holder records nothing, the content is still cut out from under the bar, and
 * the film is C1's 0.94. Norse and Vardr Labs bars are solid (film 1), so they never blur.
 *
 * One holder per page; the shell's ([LocalShellBackdrop]) is shared by the tab root showing and the tab bar. A source
 * that leaves composition marks the holder unrecorded, so a pane never draws a page that has gone.
 *
 * D-226 (L36): a second kind of holder, the window's ART ([LocalArtBackdrop]): Aurora's page art (sky, curtains, stars)
 * is its source and every Aurora glass card a pane (iOS's `.ultraThinMaterial` blurs the art behind a card). Several
 * pages can draw art at once (a pushed page over its parent, a sheet); the topmost, the one attached last, owns the
 * holder and records ([owners], [artOwner]); one that leaves hands it back to the page under it. The art is blurred
 * ONCE per frame for every card ([blurred], [recordArtBlur]: the whole art at 1 / [ART_BLUR_SCALE] of its size under
 * the blur at the same scale), and each card draws that layer scaled back up at its own place in the window: a blur per
 * card, re-recorded per art frame and per scroll frame, tripled Balance's scroll frame time on the look's emulator
 * (90th percentile 150 ms against 42 to 46 for the base build, back to back; .drafts/d226/t3).
 */
@Stable
class Backdrop internal constructor(internal val content: GraphicsLayer?, internal val blurred: GraphicsLayer? = null) {
    /** Whether [content] holds the drawing of a source still on screen. State: a pane redraws when it changes. */
    internal var recorded by mutableStateOf(false)
    /** D-250: bumped when a source goes away. Every source reads it while drawing, so during a page's slide (two pages
     *  record into one backdrop for a moment) the page that stays draws, and records, again once the other has gone,
     *  and the bars never keep the leaving page's picture. */
    internal var sourcesGone by mutableIntStateOf(0)
    /** The source's place in the window, px. */
    internal var sourceOffset = Offset.Zero
    /** The bars over this page. State: a bar that arrives or leaves redraws the source (its cut-out changes). */
    internal val panes = mutableStateListOf<Pane>()
    /** D-226: the art sources attached to an art holder, oldest first; the last one records ([artOwner]). State: a
     *  source that becomes the owner redraws, and so records. */
    internal val owners = mutableStateListOf<Any>()

    internal class Pane(val blur: GraphicsLayer?, val marginPx: Float) {
        /** The pane's ground in the window, px. */
        var window = Rect.Zero
        /** Whether this pane blurs (Aurora on API 31+); a solid or film-only pane only cuts the content out. */
        var blurs = false
        /** Whether the pane's ground is on now (read inside the source's draw, so its change redraws the source). */
        var active: () -> Boolean = { false }
    }

    /** Re-records [pane]'s blur layer: the source's layer, offset to the pane's rect and a margin of the blur radius
     *  round it (so the blur's edge samples real content, not the layer's edge). Called by the source on every draw,
     *  so the blur follows the scroll in the same frame. */
    internal fun recordBlur(scope: DrawScope, pane: Pane) {
        val layer = pane.blur ?: return
        val source = content ?: return
        val w = pane.window
        if (w.width <= 0f || w.height <= 0f) return
        val m = pane.marginPx
        val size = IntSize(ceil(w.width + 2 * m).toInt(), ceil(w.height + 2 * m).toInt())
        layer.record(scope, scope.layoutDirection, size) {
            translate(sourceOffset.x - w.left + m, sourceOffset.y - w.top + m) { drawLayer(source) }
        }
    }

    /** D-226: re-records the art holder's [blurred] layer from [content]: the art at 1 / [ART_BLUR_SCALE] of its size,
     *  under [TabBarGround.BLUR_RADIUS] at the same scale (so, drawn back up, it is the 20 dp blur). Called by the art's
     *  owner on every draw. */
    internal fun recordArtBlur(scope: DrawScope) {
        val layer = blurred ?: return
        val source = content ?: return
        if (source.size.width <= 0 || source.size.height <= 0) return
        val k = ART_BLUR_SCALE
        val r = with(scope) { TabBarGround.BLUR_RADIUS.dp.toPx() } / k
        layer.renderEffect = BlurEffect(r, r, TileMode.Clamp)
        val size = IntSize(ceil(source.size.width / k).toInt(), ceil(source.size.height / k).toInt())
        layer.record(scope, scope.layoutDirection, size) { scale(1f / k, 1f / k, Offset.Zero) { drawLayer(source) } }
    }

    companion object {
        /** D-226: how much smaller the art is blurred than drawn: a 20 dp blur (52 px at 2.625) has no detail a quarter
         *  size loses, and the offscreen is a sixteenth of the window's (.drafts/d226/art.py section 51: the measured
         *  cost, and the residue, a 1/255 step inside a card over a smooth curtain). */
        const val ART_BLUR_SCALE = 4f
    }
}

/** D-220: the shell's holder, shared by the tab root on screen (and a group's page) and the tab bar. Null outside it. */
val LocalShellBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** D-226 (L36): the window's art holder: the topmost Aurora page art records into it, Aurora's glass cards draw it
 *  blurred. Provided once at the activity's root, over every route. Null outside it (the card draws its film alone). */
val LocalArtBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** D-226 fix wave (Task 3 review): the art holder's ownership rule, pure (ThemeArtTest proves it as behaviour). The
 *  sources attached to a holder, oldest first: the last attached owns it and records; one that leaves, wherever it is,
 *  hands it to the one under the top; the holder stays recorded only while one is left. */
internal object ArtOwners {
    fun join(owners: MutableList<Any>, token: Any) {
        owners.add(token)
    }

    /** Removes [token]; whether the holder still has a source (false: mark it unrecorded). */
    fun leave(owners: MutableList<Any>, token: Any): Boolean {
        owners.remove(token)
        return owners.isNotEmpty()
    }

    fun owns(owners: List<Any>, token: Any): Boolean = owners.lastOrNull() === token
}

/** D-226: joins [backdrop]'s owners while in composition and returns whether this source is the topmost (the last
 *  attached), the one that records ([ArtOwners]). Leaving hands ownership to the one under it; the last to leave marks
 *  the holder unrecorded, so a card never draws art that has gone. */
@Composable
internal fun artOwner(backdrop: Backdrop?): () -> Boolean {
    val token = remember { Any() }
    DisposableEffect(backdrop) {
        backdrop?.owners?.let { ArtOwners.join(it, token) }
        onDispose {
            if (backdrop != null && !ArtOwners.leave(backdrop.owners, token)) backdrop.recorded = false
        }
    }
    return { backdrop != null && ArtOwners.owns(backdrop.owners, token) }
}

/** D-226: the window's art holder ([LocalArtBackdrop]): the art and its one shared blur, recorded only where
 *  [TabBarGround.blurs]. */
@Composable
fun rememberArtBackdrop(): Backdrop {
    val blurs = TabBarGround.blurs(Build.VERSION.SDK_INT)
    val content = if (blurs) rememberGraphicsLayer() else null
    val blurred = if (blurs) rememberGraphicsLayer() else null
    return remember(content, blurred) { Backdrop(content, blurred) }
}

/** A holder for one page. It always exists; it records (and so blurs) only where [TabBarGround.blurs]. */
@Composable
fun rememberBackdrop(): Backdrop {
    val layer = if (TabBarGround.blurs(Build.VERSION.SDK_INT)) rememberGraphicsLayer() else null
    return remember(layer) { Backdrop(layer) }
}

/** The page's scrolling content: put it on the viewport-sized node, before the scroll modifier. */
@Composable
fun Modifier.backdropSource(backdrop: Backdrop): Modifier {
    DisposableEffect(backdrop) {
        onDispose {
            backdrop.recorded = false
            backdrop.sourcesGone++
        }
    }
    return this
        .onGloballyPositioned { backdrop.sourceOffset = it.positionInWindow() }
        .drawWithContent {
            @Suppress("UNUSED_VARIABLE") val gone = backdrop.sourcesGone
            val layer = backdrop.content
            val panes = backdrop.panes.toList()
            val blurring = layer != null && panes.any { it.blurs }
            if (blurring) {
                layer!!.record { this@drawWithContent.drawContent() }
                if (!backdrop.recorded) backdrop.recorded = true
                panes.forEach { if (it.blurs) backdrop.recordBlur(this, it) }
            }
            val cuts = panes.filter { it.active() }.map { it.window.translate(-backdrop.sourceOffset) }
            cutOut(cuts, 0) { if (blurring) drawLayer(layer!!) else this@drawWithContent.drawContent() }
        }
}

/** Draws [block] with every rect of [cuts] clipped out. */
private fun DrawScope.cutOut(cuts: List<Rect>, i: Int, block: DrawScope.() -> Unit) {
    if (i == cuts.size) {
        block()
        return
    }
    val r = cuts[i]
    clipRect(r.left, r.top, r.right, r.bottom, ClipOp.Difference) { cutOut(cuts, i + 1, block) }
}

/**
 * A bar's ground on [backdrop]: the blurred page (when [blurs] and the page is recorded), then [film], all at [alpha]
 * (the ground's fade-in). [rect] is the ground in the node's own px (a navigation bar's reaches up under the status
 * bar); [active] says whether the ground is on, so the page is cut out from under it. Without a [backdrop] it draws
 * the film alone.
 *
 * D-226: [shape] clips the blur and the film (a glass card's rounded rectangle; a bar's is the rectangle);
 * [filmUnblurred] is drawn instead of [film] when no blur is drawn (a card's 0.82 below API 31 or with no art
 * recorded); [cap] caps the blurred page and the art under it per channel (fix wave 2, F2: a Darken drawn over the
 * blurred page and under the film; white is none; [BarDecisions.navBandCap]). On an art holder the pane draws the
 * holder's shared blur scaled up through a layer of its own ([artLayer]) whose translation is the art's origin less
 * the pane's, set where the pane is placed: a card scrolling over a still art samples the art behind it with no redraw
 * (a state written per move, read by the draw, cost 4 to 7 ms a scroll frame on the look's emulator). Fix wave (M3 b):
 * that layer is drawn inside a second one ([clipLayer]) clipped by its outline (the RenderNode's own round-rect clip,
 * fixed in the pane's place, so a move changes only [artLayer]'s translation), not by a Path built per draw.
 */
@Composable
fun Modifier.backdropGlass(
    backdrop: Backdrop?,
    film: Color,
    blurs: Boolean,
    alpha: () -> Float = { 1f },
    active: () -> Boolean = { true },
    rect: Density.(Size) -> Rect = { Rect(Offset.Zero, it) },
    shape: Shape = RectangleShape,
    filmUnblurred: Color = film,
    cap: Color = Color.White,
): Modifier {
    val density = LocalDensity.current
    val radiusPx = with(density) { TabBarGround.BLUR_RADIUS.dp.toPx() }
    // D-226: an art holder's panes (glass cards) draw its one shared blur; a page holder's (bars) each blur their own.
    val shared = backdrop?.blurred
    val blurLayer = if (backdrop?.content != null && shared == null) rememberGraphicsLayer() else null
    val artLayer = if (shared != null) rememberGraphicsLayer() else null
    val clipLayer = if (shared != null) rememberGraphicsLayer() else null
    val pane = remember(backdrop, blurLayer, radiusPx) {
        Backdrop.Pane(blurLayer, radiusPx).also { blurLayer?.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp) }
    }
    val isActive by rememberUpdatedState(active)
    pane.blurs = blurs && (blurLayer != null || shared != null)
    pane.active = { isActive() }
    DisposableEffect(backdrop, pane) {
        backdrop?.panes?.add(pane)
        onDispose { backdrop?.panes?.remove(pane) }
    }
    return this
        .onGloballyPositioned { c ->
            val local = with(density) { rect(Size(c.size.width.toFloat(), c.size.height.toFloat())) }
            val origin = c.positionInWindow()
            pane.window = local.translate(origin)
            if (artLayer != null && backdrop != null) {
                artLayer.translationX = backdrop.sourceOffset.x - origin.x
                artLayer.translationY = backdrop.sourceOffset.y - origin.y
            }
        }
        .drawBehind {
            val a = alpha()
            if (a <= 0f) return@drawBehind
            val r = rect(size)
            val layer = pane.blur
            val blurred = pane.blurs && backdrop?.recorded == true && !pane.window.isEmpty
            // The pane's outline at the ground's size (drawn at the ground's place).
            val outline = shape.createOutline(r.size, layoutDirection, this@drawBehind)
            if (blurred) {
                if (shared != null && artLayer != null && clipLayer != null) {
                    // The art's blur at the window's size, placed by the layer's translation (the node's origin in the
                    // window is the ground's, less the ground's place in the node).
                    val k = Backdrop.ART_BLUR_SCALE
                    val origin = pane.window.topLeft - r.topLeft
                    artLayer.translationX = backdrop!!.sourceOffset.x - origin.x
                    artLayer.translationY = backdrop.sourceOffset.y - origin.y
                    artLayer.alpha = a
                    val whole = IntSize((shared.size.width * k).toInt(), (shared.size.height * k).toInt())
                    artLayer.record(this, layoutDirection, whole) { scale(k, k, Offset.Zero) { drawLayer(shared) } }
                    if (clipLayer.clipToOutline(outline, r.topLeft)) {
                        val node = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
                        clipLayer.record(this, layoutDirection, node) { drawLayer(artLayer) }
                        drawLayer(clipLayer)
                    } else {
                        clipPath(Path().apply { addOutline(outline); translate(r.topLeft) }) { drawLayer(artLayer) }
                    }
                } else if (layer != null) {
                    layer.alpha = a
                    clipPath(Path().apply { addOutline(outline); translate(r.topLeft) }) {
                        translate(r.left - pane.marginPx, r.top - pane.marginPx) { drawLayer(layer) }
                    }
                }
            }
            // Fix wave 2 (F2, .drafts/d226/art.py section 55): every pixel under the ground, the blurred page over the art,
            // is capped at [cap] per channel (Darken over what is drawn, all opaque here), so light content reads as the
            // cap under the film and nothing darker changes; at the ground's alpha, so it fades in with it.
            if (blurred && cap != Color.White) translate(r.left, r.top) { drawOutline(outline, cap.copy(alpha = a), blendMode = BlendMode.Darken) }
            val f = if (blurred) film else filmUnblurred
            translate(r.left, r.top) { drawOutline(outline, f.copy(alpha = f.alpha * a)) }
        }
}

/** Fix wave (M3 b): clips this layer to [outline] placed at [at] in its own coordinates, by the RenderNode's outline (a
 *  hardware rectangle or round-rectangle clip, no Path). False for any other shape (the caller clips by a path). */
private fun GraphicsLayer.clipToOutline(outline: Outline, at: Offset): Boolean {
    when (outline) {
        is Outline.Rectangle -> setRectOutline(at + outline.rect.topLeft, outline.rect.size)
        is Outline.Rounded -> {
            val rr = outline.roundRect
            if (!rr.isSimple) return false
            setRoundRectOutline(at + Offset(rr.left, rr.top), Size(rr.width, rr.height), rr.topLeftCornerRadius.x)
        }
        else -> return false
    }
    clip = true
    return true
}
