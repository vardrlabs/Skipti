package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/**
 * D-176: "Aurora" (ADR-0038 revised, the founder, 2026-09-26), the twin of
 * iOS's `Aurora`, `AuroraBackdrop` and `GlassCard` in DesignTokens.swift.
 * D-214: the page's ground moves now (curtains over a dawn or a night); it is drawn by ThemeMotion.kt.
 */
object Aurora {
    /** D-180: the film under the hero's chips (the hero's own text colour at
     *  12%); keeps that text at 4.5:1 or better over every stop, in every set
     *  (18% fell to 4.35:1 over the old rose one). */
    const val HERO_CHIP_ALPHA = 0.12f
}

/** D-212: the hero card's ground, the theme's own (tokens, measured). */
fun heroBrush(dark: Boolean): Brush =
    Brush.linearGradient(listOf(Tok.heroStart(dark), Tok.heroMid(dark), Tok.heroEnd(dark)))

/** D-212: the theme's radius; Aurora keeps a radius a call site asks for. */
fun cardRadius(asked: Int?, theme: AppTheme): Int =
    if (theme.hasGlass) asked ?: theme.cardRadius else theme.cardRadius

/** The page ground (D-214): Aurora's curtains over a dawn or a starry night, Norse's rising runes, Vardr Labs' still
 *  dot grid (D-213). The art is in ThemeMotion.kt's [pageArt]; the twin of iOS's `AuroraBackdrop`. */
fun Modifier.auroraBackground(dark: Boolean): Modifier = pageArt(dark)

/** D-213: Vardr Labs' still dot grid over the page colour (art.py measures text over its darkest dot). Fix wave 3:
 *  drawn out under the system bars like the other grounds ([Bleed]); the dots are laid out once per bled size. */
internal fun Modifier.gridBackground(dark: Boolean, bleed: State<Bleed>): Modifier = composed {
    val bg = Tok.bg(dark)
    val dot = Tok.accent(dark).copy(alpha = ThemeArt.m("gridAlpha"))
    drawWithCache {
        val pitch = ThemeArt.m("gridPitch").dp.toPx()
        val r = ThemeArt.m("gridDot").dp.toPx()
        var laidOut = Size.Unspecified
        var dots = emptyList<Offset>()
        onDrawBehind {
            val b = bleed.value
            inset(-b.left, -b.top, -b.right, -b.bottom) {
                if (size != laidOut) { laidOut = size; dots = ArtGeometry.gridDots(size.width, size.height, pitch) }
                drawRect(bg)
                dots.forEach { drawCircle(dot, radius = r, center = it) }
            }
        }
    }
}

/** D-176: a frosted card: the surface over the glow, so text keeps
 *  the contrast measured against the surface. D-226 (L36, A1, .drafts/d226/art.py sections 50 to 53): Aurora's glass
 *  is iOS's: the page art behind the card blurred (API 31+, [LocalArtBackdrop] through [backdropGlass], clipped to the
 *  card's shape) under the surface at `GlassLook.film(true)` (0.78), or the film alone at `GlassLook.film(false)`
 *  (0.82), then in dark iOS's lift (`GlassLook.lift`, fix wave F4); a 1 dp white rim at `GlassLook.rim(dark)` drawn inside the shape (D28); iOS's shadow (black at
 *  `glassShadowAlpha` times Android's measured gain (`GlassLook.shadowAlpha`, fix wave F3), y `glassShadowY`, sigma `glassShadowSigma` through [ShadowBlur]) in both modes. D-212: glass is Aurora's
 *  alone; Norse and Vardr Labs get a flat surface with a hairline, and the
 *  theme's own corners (a call site's [radius] holds only in Aurora).
 *  D-213 (fix wave 2): [tint] is a wash on the card's own shape, between its fill and its strokes (iOS's
 *  `glassCard(tint:)`), so a tinted card (a group's header) keeps Norse's notches and edge and Vardr Labs'
 *  frame marks on top. D-222 (L57): [padding] in dp, 16 unless asked (You's list pads 0 and lays out its own rows,
 *  the twin of iOS's `glassCard(padding:)`). */
fun Modifier.glassCard(dark: Boolean, radius: Int? = null, tint: Color? = null, padding: Int = 16): Modifier {
    val theme = Appearance.theme
    return when (CardStyle.of(theme)) {
        CardStyle.GLASS -> {
            val shape = RoundedCornerShape(cardRadius(radius, theme).dp)
            this
                .fillMaxWidth()
                .composed {
                    val art = LocalArtBackdrop.current
                    Modifier
                        .dropShadow(shape) {
                            this.radius = ShadowBlur.radiusDp(ThemeArt.m("glassShadowSigma"), density).dp.toPx()
                            offset = Offset(0f, ThemeArt.m("glassShadowY").dp.toPx())
                            color = Color.Black
                            alpha = GlassLook.shadowAlpha()
                        }
                        .backdropGlass(
                            art, film = Tok.surface(dark).copy(alpha = GlassLook.film(true)), blurs = true,
                            active = { false }, shape = shape, filmUnblurred = Tok.surface(dark).copy(alpha = GlassLook.film(false)),
                        )
                }
                .background(Color.White.copy(alpha = GlassLook.lift(dark)), shape)
                .background(tint ?: Color.Transparent, shape)
                .border(1.dp, Color.White.copy(alpha = GlassLook.rim(dark)), shape)
                .padding(padding.dp)
        }
        // D-213: Norse's notched corners on the Look Book's dark gradient and gold-brown edge. D-226 (L35): the edge on
        // the four straight sides only ([notchEdge]), as the Look Book's clip-path cuts the card's border at the corners.
        CardStyle.NOTCHED -> {
            val shape = NotchedShape(ThemeArt.m("chamfer").dp)
            this
                .fillMaxWidth()
                // D-243 (T7): the static gold glow outside the edge, beneath the fill.
                .norseEdgeGlow(Tok.accent(dark), ThemeArt.m("chamfer"))
                .background(Brush.verticalGradient(listOf(ThemeArt.color("norseCardTop"), ThemeArt.color("norseCardBottom"))), shape)
                .background(tint ?: Color.Transparent, shape)
                .notchEdge(ThemeArt.color("norseEdge"), ThemeArt.m("chamfer"))
                .padding(padding.dp)
        }
        // D-213: Vardr Labs' square card with a frame mark at each corner.
        CardStyle.FRAMED -> {
            val mark = Tok.textDim(dark).copy(alpha = ThemeArt.m("frameAlpha"))
            this
                .fillMaxWidth()
                .background(Tok.surface(dark), RectangleShape)
                .background(tint ?: Color.Transparent, RectangleShape)
                .border(1.dp, Tok.hairline(dark), RectangleShape)
                .drawBehind {
                    val px = 1.dp.toPx()
                    ArtGeometry.frameMarks(size.width, size.height, ThemeArt.m("frameInset").dp.toPx(), ThemeArt.m("frameLength").dp.toPx())
                        .forEach { (a, b) -> drawLine(mark, a, b, strokeWidth = px) }
                }
                .padding(padding.dp)
        }
    }
}
