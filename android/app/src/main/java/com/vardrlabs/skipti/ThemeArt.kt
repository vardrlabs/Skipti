package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** D-213 (E2, ADR-0041 "Theme art", the founder, 2026-10-15): the runes the
 *  themes draw; the twin of iOS's `Runes`. D-215: drawn in our own Vardr Runes
 *  Carved Regular, bundled (`Type.runes`; ThemeArtTest proves every one), so both
 *  phones draw the same shapes. */
object Runes {
    const val BALANCE = "\u16A0" // Fehu
    const val EXPENSES = "\u16B7" // Gebo
    const val GROUPS = "\u16D7" // Mannaz
    const val SETTLE = "\u16CF" // Tiwaz
    const val SEPARATOR = "\u16EB" // single punctuation
    const val SECTION_MARK = "\u16DF" // Othala
    const val ORNAMENT = "\u16DC" // Ingwaz

    /** The 24 runes of the Elder Futhark, in order (D-214 draws its drift from them). */
    val FUTHARK = listOf(
        "\u16A0", "\u16A2", "\u16A6", "\u16A8", "\u16B1", "\u16B2", "\u16B7", "\u16B9",
        "\u16BA", "\u16BE", "\u16C1", "\u16C3", "\u16C7", "\u16C8", "\u16C9", "\u16CA",
        "\u16CF", "\u16D2", "\u16D6", "\u16D7", "\u16DA", "\u16DC", "\u16DE", "\u16DF",
    )

    /** Every rune SKIPTI draws. */
    val ALL = FUTHARK + SEPARATOR
}

/** D-215: where a rune meets words. iOS gives every text face a Core Text cascade that ends in Vardr Runes; Android
 *  cannot (a Compose `FontFamily` does not fall back per glyph, and `Typeface.CustomFallbackBuilder` needs API 29 on
 *  a minSdk of 26), so every place a rune can reach visible text names the rune font on the rune character ALONE,
 *  through this one builder. The span carries no colour and no size (the line's own colour and sp apply, so it scales
 *  with the text, as iOS's separator does with its body) and pins Regular with no synthesis: the font has one weight,
 *  and a rune inside a bold line must not be emboldened (iOS never does). A colour is the caller's own span. */
object RuneSpans {
    val style = SpanStyle(
        fontFamily = Type.runes,
        fontWeight = FontWeight.Normal,
        fontSynthesis = androidx.compose.ui.text.font.FontSynthesis.None,
    )

    /** The Runic block the font covers, U+16A0 to U+16F8 (all in the BMP: one char each). */
    fun isRune(c: Char): Boolean = c in '\u16A0'..'\u16F8'
}

/** Appends [text], the rune font on each rune character and nothing on the rest. */
fun AnnotatedString.Builder.appendWithRunes(text: String) {
    val start = length
    append(text)
    text.forEachIndexed { i, ch -> if (RuneSpans.isRune(ch)) addStyle(RuneSpans.style, start + i, start + i + 1) }
}

/** A plain string that may carry a rune (a meta line built as a String), drawn with the rune font on the rune only
 *  and the line's own colour. The form every `Text(` that shows such a string takes. */
fun String.withRunes(): AnnotatedString = buildAnnotatedString { appendWithRunes(this@withRunes) }

/** D-224 (L49, .drafts/d224/art.py section 37): the done check's tick, three points in the check's `doneCheck` 104
 *  box, drawn with round caps and joins. The twin of iOS's `DoneCheck.tick`; ThemeArtTest compares. */
object DoneCheck {
    val TICK: List<Pair<Double, Double>> = listOf(32.0 to 54.0, 46.0 to 67.0, 73.0 to 39.0)

    /** How long a screen's check runs its clock, in seconds: until it lands, except Norse's ring, which keeps turning
     *  while the ART ticks (D-239 review I1: that endless turn is decoration, so battery saver stops it where it is).
     *  The twin of iOS's `DoneCheck.moving`. */
    fun runFor(theme: AppTheme, artTicks: Boolean): Double =
        if (theme == AppTheme.NORSE && artTicks) Double.POSITIVE_INFINITY else CheckMotion.POP_AT + CheckMotion.POP_DURATION
}

/** D-213: the art's own colours and sizes, the twin of iOS's `ThemeArt`
 *  (ThemeArtTest compares them); derived and measured by .drafts/d213/art.py. */
object ThemeArt {
    val HEX = mapOf(
        "norseEdge" to "#6B5426", "norseCardTop" to "#1A1710", "norseCardBottom" to "#0F1015", "norseSilver" to "#9AA2AE",
        "diamondTop" to "#F2D37A", "diamondBottom" to "#B4851F",
        "boltDark0" to "#E3EAF0", "boltDark1" to "#9DB0BF", "boltDark2" to "#6C7F8E", "boltDark3" to "#C3CFD8",
        "boltLight0" to "#8DA0AF", "boltLight1" to "#4C6172", "boltLight2" to "#24333F", "boltLight3" to "#6C7F8E",
        // D-221 (art.py sections 18 and 19): Norse's unlit runes on the ring, and the gap between its meter's segments.
        "runeUnlit" to "#4A4740", "norseMeterGap" to "#1A1A1C",
        // D-224 (.drafts/d224/art.py section 37): Norse's fill inside the done check (the Look Book's 247).
        "norseCheckFill" to "#18120A",
    )

    // D-226 (.drafts/d226/art.py section 46, the founder's Q8 a): the art lengths D-213 copied 1:1 from the Look Book's
    // px are scaled by its small-type scale (x 1.28): chamfer, frameInset, frameLength, gridPitch, gridDot, diamondRadius,
    // tileRadius, ornamentRune and pillChamfer.
    // tabRune 34 (G1, the founder's look: the runes read too small in their tile): Vardr Runes Carved inks 0.70 em, so 34 dp stands 23.8 dp in the 30 dp slot.
    val METRICS = mapOf(
        "chamfer" to 10.0, "frameInset" to 4.0, "frameLength" to 12.0, "frameAlpha" to 0.7,
        "gridPitch" to 18.0, "gridDot" to 0.77, "gridAlpha" to 0.2025,
        "diamondSide" to 64.0, "diamondRadius" to 8.0, "tileRadius" to 3.0, "boltWidth" to 72.0, "boltHeight" to 64.0,
        "boltStartX" to 0.183, "boltStartY" to -0.119, "boltEndX" to 0.817, "boltEndY" to 1.119,
        "boltDark1At" to 0.38, "boltDark2At" to 0.62, "boltLight1At" to 0.45, "boltLight2At" to 0.70,
        "reticleDark" to 0.18, "reticleLight" to 0.25, "outlineLight" to 0.75, "outlineDark" to 0.5,
        "ornamentRune" to 17.0, "tabRune" to 34.0, "reticleGlyph" to 32.0,
        "pillChamfer" to 5.0, "pillOutline" to 0.4,
        // D-221 (art.py sections 18, 19, 21, 22): the ring round You's picture, the settled meter's heights and Norse's
        // segments, Vardr Labs' progress dot, the milestone's seal and the group page's header tile.
        "ringBox" to 137.0, "ringRadius" to 62.0, "ringStroke" to 6.0, "runeRingRadius" to 60.0, "runeRingSize" to 14.0,
        "meterBig" to 10.0, "meterBigVardr" to 7.0, "meterSmall" to 6.0, "meterSmallVardr" to 4.0, "meterSegment" to 11.0,
        "meterGap" to 2.0, "progressDot" to 7.0, "progressDotGap" to 7.0, "seal" to 44.0, "groupTile" to 48.0,
        // D-222 (.drafts/d222/art.py sections 24 to 28): You's icon tiles and list rhythm, the theme cards and swatches,
        // the mode control (its touch row, track, inset and the chosen segment's shadow) and the text-size slider.
        "settingsTile" to 33.0, "settingsGlyph" to 19.0, "listPadH" to 15.0, "listPadV" to 3.0, "rowPadV" to 10.0, "rowGap" to 13.0,
        "themePad" to 8.0, "themeGap" to 6.0, "swatchHeight" to 59.0, "swatchRadius" to 10.0, "swatchRune" to 20.0,
        "swatchRuneTracking" to 0.12, "swatchGridPitch" to 8.0, "swatchGridDot" to 0.9, "swatchMarkInset" to 6.0,
        "swatchMarkLength" to 10.0, "modeTrack" to 40.0, "modeInset" to 4.0, "touchTarget" to 48.0, "segmentShadowAlpha" to 0.15,
        "segmentShadowRadius" to 2.0, "segmentShadowY" to 1.0, "sliderTrack" to 5.0, "sliderThumb" to 20.0, "sliderRing" to 2.5,
        // D-223 (.drafts/d223/art.py sections 30 to 34): Home's ledger grid and card (tile, tile to name, inside the card,
        // between cards), Lately's rows (avatar, avatar to words, between rows), the empty state's icon, the Groups card's spacing.
        "ledgerTile" to 33.0, "ledgerTileGap" to 9.0, "ledgerCardGap" to 6.0, "ledgerGridGap" to 10.0, "latelyAvatar" to 33.0,
        "latelyAvatarGap" to 12.0, "latelyRowGap" to 11.0, "emptyIcon" to 44.0, "groupsCardSpacing" to 12.0,
        // D-224 (.drafts/d224/art.py sections 37 to 39): the big done check (its box, circle and tick), the small toast
        // (shape, padding, gap, dot, glyph, stroke, side inset, its place clear of the bar, its shadow).
        "doneCheck" to 104.0, "doneCircleRadius" to 48.0, "doneCircleStroke" to 3.0, "doneTickStroke" to 6.0,
        "toastRadius" to 18.0, "toastPadV" to 12.0, "toastPadH" to 15.0, "toastGap" to 10.0, "toastDot" to 26.0,
        "toastGlyph" to 15.0, "toastCheckStroke" to 3.5, "toastInset" to 16.0, "toastClearOfBar" to 104.0,
        "toastShadowAlpha" to 0.14, "toastShadowRadius" to 9.0, "toastShadowY" to 6.0,
        // D-225 (.drafts/d225/art.py sections 41 to 45): the list row's line gap, the drawn switch (track, thumb, inset,
        // off border, slide time), the emoji grid (columns, gap, glyph, chosen border and corner), the picture's
        // placeholder ratios, the lock screen's glyph and the clear cross.
        "rowLineGap" to 2.0, "switchWidth" to 51.0, "switchHeight" to 31.0, "switchThumb" to 27.0, "switchInset" to 2.0,
        "switchBorder" to 1.5, "switchTravel" to 0.2, "emojiColumns" to 6.0, "emojiGap" to 8.0, "emojiGlyph" to 24.0,
        "emojiBorder" to 2.0, "emojiRadius" to 8.0, "avatarGlyph" to 0.5, "avatarEmoji" to 0.55, "lockGlyph" to 40.0,
        "clearGlyph" to 18.0,
        // D-226 (.drafts/d226/art.py sections 46, 51 and 53): the Norse hero's inner frame and its watermark rune (size,
        // its trailing and top place, scaled x 1.28), and Aurora's glass (iOS's film over its material, Android's film
        // with and without the art blur, iOS's rim, Android's rim in light and dark (the dark one measured on iOS in
        // Task 0), the card shadow's sigma, offset and alpha).
        "heroFrameInset" to 6.0, "heroWatermark" to 118.0, "heroWatermarkTrailing" to 13.0, "heroWatermarkTop" to -8.0,
        "glassFilm" to 0.78, "glassFilmNoBlur" to 0.82, "glassRim" to 0.35, "glassRimLight" to 0.35, "glassRimDark" to 0.35,
        "glassShadowSigma" to 12.0, "glassShadowY" to 4.0, "glassShadowAlpha" to 0.06,
        // D-216 (.drafts/d216/art.py section 57): the App icon rows' preview tile, its side and its corner as a share of
        // the side (Apple's icon ratio; the same shape in every theme).
        "iconPreview" to 36.0, "iconPreviewRadius" to 0.225,
        // D-243 (T7, .drafts/d214/motion.py section 1f; the approved Vibrant mock-up's drop-shadow(0 0 7px accent 0.18)):
        // Norse's static glow outside a card's edge: its peak alpha, its Gaussian sigma (dp, the CSS blur / 2) and the
        // width of each drawn band (dp). Sigma 3.0, not the mock-up's 3.5: the gap audit's tightest page-ground text
        // beside a card is 6 pt (iOS's Add a person labels; .drafts/d243/gap-audit.md), so the reach must fit 6 (plan
        // R2 option 1). Twelve bands, 6 dp: page-ground text keeps that far from a Norse card.
        "norseEdgeGlow" to 0.18, "norseEdgeGlowSigma" to 3.0, "norseEdgeGlowBand" to 0.5,
    )

    /** G1: a rune's line box runs ascent 900 to descent 250 (units per 1000), so its middle sits 325 above the baseline and
     *  its ink (cap 0 to 700) middle 350: the tab rune moves down by this share of its size to centre by its ink. */
    const val RUNE_INK_SHIFT_EM = (350f - (900f - 250f) / 2f) / 1000f
    val tabRuneInkShift: Float get() = m("tabRune") * RUNE_INK_SHIFT_EM

    fun color(name: String): Color = Color(0xFF000000 or HEX.getValue(name).removePrefix("#").toLong(16))
    fun m(name: String): Float = METRICS.getValue(name).toFloat()

    /** The hex bolt's brushed steel: the Look Book's dark stops, or its darker light ones (D-213). */
    fun boltStops(dark: Boolean): List<Pair<Float, Color>> {
        val p = if (dark) "boltDark" else "boltLight"
        return listOf(0f to color(p + "0"), m(p + "1At") to color(p + "1"), m(p + "2At") to color(p + "2"), 1f to color(p + "3"))
    }

    fun reticleAlpha(dark: Boolean): Float = m(if (dark) "reticleDark" else "reticleLight")

    /** G1: the kind of shape a button or an input takes (twin of iOS's `controlKind`); see [controlShape]. */
    fun controlKind(theme: AppTheme, radius: Int? = null): ControlShapeKind =
        if (theme == AppTheme.NORSE) ControlShapeKind.Notched(m("chamfer")) else ControlShapeKind.Rounded(radius ?: theme.buttonRadius)

    /** G1 (the founder, 2026-10-15): the shape of a button or an input. Norse notches it like its cards (chamfer 10 since D-226);
     *  Aurora and Vardr Labs keep their rounded rectangle, or the call site's own [otherwise] (an input's Material
     *  default). The ONE decision: every button and input asks for it, so a new one cannot miss it (ThemeArtTest
     *  reads the sources). */
    fun controlShape(theme: AppTheme, otherwise: Shape? = null): Shape =
        if (theme == AppTheme.NORSE) NotchedShape(m("chamfer").dp) else otherwise ?: RoundedCornerShape(theme.buttonRadius.dp)

    /** G1 fix 1 (the founder: "anything rectangular in Norse"): an inner panel or stat tile takes the cards' notch too.
     *  The cut is one rule, here: `chamfer` on a panel taller than 50 dp, else 8 (so a short one is not over-cut); the
     *  geometry clamps it to half the short side. Aurora and Vardr Labs keep the call site's radius. D-226 (art.py
     *  section 46): both numbers scaled with the chamfer (8 to 10), the rule's reason kept (the cut never more than a
     *  fifth of the panel: 10 / 0.20 = 50; 6 x 1.28 = 7.68, so 8). */
    const val PANEL_TALLER_THAN = 50f
    const val PANEL_SMALL_CHAMFER = 8f

    fun panelChamfer(heightDp: Float): Float = if (heightDp > PANEL_TALLER_THAN) m("chamfer") else PANEL_SMALL_CHAMFER

    /** D-220 (L20, the Look Book squares everything in a Vardr Labs card): outside Norse a panel takes
     *  `minOf(radius, theme.cardRadius)`: Aurora keeps the call site's 10 to 18 (under its 20), Vardr Labs is square. */
    fun panelKind(theme: AppTheme, radius: Int, heightDp: Float): ControlShapeKind =
        if (theme == AppTheme.NORSE) ControlShapeKind.Notched(panelChamfer(heightDp)) else ControlShapeKind.Rounded(minOf(radius, theme.cardRadius))

    fun panelShape(theme: AppTheme, radius: Int): Shape =
        if (theme == AppTheme.NORSE) PanelShape else RoundedCornerShape(minOf(radius, theme.cardRadius).dp)

    /** D-220 (L19, E4 reuses it): the shape of a pill (a chip, a role word, a count): Aurora a capsule, Norse notched at
     *  [pillChamfer], Vardr Labs square. ONE decision: every pill asks for it. Twin of iOS's `ThemeArt.pillKind`. */
    fun pillKind(theme: AppTheme): PillKind = when (theme) {
        AppTheme.AURORA -> PillKind.CAPSULE
        AppTheme.NORSE -> PillKind.NOTCHED
        AppTheme.VARDR -> PillKind.SQUARE
    }

    val pillChamfer: Float get() = m("pillChamfer")

    fun pillShape(theme: AppTheme): Shape = when (pillKind(theme)) {
        PillKind.CAPSULE -> RoundedCornerShape(50)
        PillKind.NOTCHED -> NotchedShape(pillChamfer.dp)
        PillKind.SQUARE -> RectangleShape
    }

    /** D-220 (L22): a meter (the category bar, E4's settled meter): a capsule in Aurora, square elsewhere. */
    fun meterKind(theme: AppTheme): MeterKind = if (theme == AppTheme.AURORA) MeterKind.CAPSULE else MeterKind.SQUARE

    fun meterShape(theme: AppTheme): Shape = if (meterKind(theme) == MeterKind.CAPSULE) RoundedCornerShape(50) else RectangleShape

    /** The legend swatch's corner in dp: 2 in Aurora, square elsewhere. */
    fun swatchRadius(theme: AppTheme): Int = if (theme == AppTheme.AURORA) 2 else 0

    fun swatchShape(theme: AppTheme): Shape = RoundedCornerShape(swatchRadius(theme).dp)

    /** D-220 (L21): a card: Aurora's glass a rounded rectangle at [cardRadius] (an asked radius kept), Norse's notched
     *  at `chamfer`, Vardr Labs' square. */
    fun cardKind(theme: AppTheme, radius: Int? = null): CardShapeKind = when (CardStyle.of(theme)) {
        CardStyle.GLASS -> CardShapeKind.Rounded(cardRadius(radius, theme))
        CardStyle.NOTCHED -> CardShapeKind.Notched(m("chamfer"))
        CardStyle.FRAMED -> CardShapeKind.Square
    }

    fun cardShape(theme: AppTheme, radius: Int? = null): Shape = when (val k = cardKind(theme, radius)) {
        is CardShapeKind.Rounded -> RoundedCornerShape(k.dp.dp)
        is CardShapeKind.Notched -> NotchedShape(k.dp.dp)
        CardShapeKind.Square -> RectangleShape
    }
}

/** D-220: what [ThemeArt.pillKind] decides (the chamfer is [ThemeArt.pillChamfer]). */
enum class PillKind { CAPSULE, NOTCHED, SQUARE }

/** D-220: a pill is filled in Aurora and Norse and outlined in Vardr Labs (LB 269), as [ButtonLook] does a button. */
enum class PillLook {
    FILLED, OUTLINED;

    companion object {
        fun of(theme: AppTheme) = if (theme == AppTheme.VARDR) OUTLINED else FILLED
    }
}

/** D-220: the six kinds of pill SKIPTI draws. */
enum class PillTone { ACCENT, OWNER, SELECTED, UNSELECTED, HERO, PHOTO }

/** D-220: a colour a pill names: a token, the amber group tone's letter or fill, or a literal. */
sealed interface PillInk {
    data class Token(val tok: Tok) : PillInk
    object AmberLetter : PillInk
    object AmberFill : PillInk
    object White : PillInk
    object Black : PillInk

    fun of(set: ThemeSet): Color = when (this) {
        is Token -> tok.of(set)
        AmberLetter -> GroupTone.AMBER.letterOf(set)
        AmberFill -> GroupTone.AMBER.fillOf(set)
        White -> Color.White
        Black -> Color.Black
    }
}

/** D-220: what a pill draws, as data (a pure function of tone, look and ground): its words, an optional fill and an
 *  optional border drawn inside the shape ([BORDER_WIDTH] dp). The same table as iOS's `PillPaint` (both tests pin
 *  it). [onPage]: the pill sits straight on the page ground; an outlined pill there fills with `bg` so the grid's dots
 *  do not show under its words (art.py section 13). */
data class PillPaint(val words: PillInk, val fill: Layer?, val border: Layer?) {
    data class Layer(val ink: PillInk, val alpha: Float = 1f)

    companion object {
        const val BORDER_WIDTH = 1f

        fun of(tone: PillTone, look: PillLook, onPage: Boolean = false): PillPaint {
            val outline = ThemeArt.m("pillOutline")
            val page = if (onPage) Layer(PillInk.Token(Tok.bg)) else null
            val accent = PillInk.Token(Tok.accent)
            return when (tone) {
                PillTone.PHOTO -> PillPaint(PillInk.White, Layer(PillInk.Black, PhotoCard.CHIP_ALPHA), null)
                PillTone.ACCENT ->
                    if (look == PillLook.FILLED) PillPaint(accent, Layer(PillInk.Token(Tok.accentSoft)), null)
                    else PillPaint(accent, page, Layer(accent, outline))
                PillTone.OWNER ->
                    if (look == PillLook.FILLED) PillPaint(PillInk.AmberLetter, Layer(PillInk.AmberFill), null)
                    else PillPaint(PillInk.AmberLetter, page, Layer(PillInk.AmberLetter, outline))
                PillTone.SELECTED ->
                    if (look == PillLook.FILLED) PillPaint(PillInk.Token(Tok.onAccent), Layer(accent), null)
                    else PillPaint(accent, page, Layer(accent))
                PillTone.UNSELECTED ->
                    if (look == PillLook.FILLED) PillPaint(PillInk.Token(Tok.text), Layer(PillInk.Token(Tok.surface), 0.8f), Layer(PillInk.Token(Tok.hairline)))
                    else PillPaint(PillInk.Token(Tok.text), page, Layer(PillInk.Token(Tok.hairline)))
                PillTone.HERO ->
                    if (look == PillLook.FILLED) PillPaint(PillInk.Token(Tok.onHero), Layer(PillInk.Token(Tok.onHero), Aurora.HERO_CHIP_ALPHA), null)
                    else PillPaint(PillInk.Token(Tok.onHero), null, Layer(PillInk.Token(Tok.onHero), outline))
            }
        }
    }
}

/** D-225 (L-D217-9, .drafts/d225/art.py section 42): what the drawn switch paints, as tokens (a pure function of its
 *  state; one golden table in both shells' tests, by token name). ON: an accent track, a surface thumb, no border. OFF:
 *  a ringTrack track inside a `switchBorder` border in textFaint, a textDim thumb (Material's way of marking off: each
 *  state holds 3:1 without colour alone). The twin of iOS's `SwitchPaint`. */
data class SwitchPaint(val track: Tok, val border: Tok?, val thumb: Tok) {
    companion object {
        fun of(on: Boolean): SwitchPaint =
            if (on) SwitchPaint(Tok.accent, null, Tok.surface) else SwitchPaint(Tok.ringTrack, Tok.textFaint, Tok.textDim)
    }
}

/** D-225: where the switch's thumb sits, from the art values: `switchInset` plus half the thumb from the leading edge
 *  when off (15.5), the same from the trailing edge of the `switchWidth` track when on (35.5). The twin of iOS's
 *  `SwitchGeometry`. */
object SwitchGeometry {
    fun thumbCentreX(on: Boolean): Float {
        val half = ThemeArt.m("switchInset") + ThemeArt.m("switchThumb") / 2f
        return if (on) ThemeArt.m("switchWidth") - half else half
    }
}

/** D-220 (L19): draws a pill: the theme's [ThemeArt.pillShape], then [PillPaint]'s fill and its border drawn inside the
 *  shape. The call site keeps its font and padding (the padding comes AFTER this modifier, a click between them so its
 *  ripple follows the shape); the words take [ThemePills.text]. [onPage]: the pill sits straight on the page ground.
 *  Twin of iOS's `.themePill(_:onPage:)`. */
fun Modifier.themePill(tone: PillTone, dark: Boolean, onPage: Boolean = false): Modifier {
    val theme = Appearance.theme
    val shape = ThemeArt.pillShape(theme)
    val paint = PillPaint.of(tone, PillLook.of(theme), onPage)
    val set = ThemeSet.resolve(theme, dark)
    var m = clip(shape)
    paint.fill?.let { m = m.background(ThemePills.color(it, set), shape) }
    paint.border?.let { m = m.border(PillPaint.BORDER_WIDTH.dp, ThemePills.color(it, set), shape) }
    return m
}

/** D-220: the colours a pill's [PillPaint] names, resolved for this phone's theme and mode. */
object ThemePills {
    /** The pill's words. */
    fun text(tone: PillTone, dark: Boolean): Color {
        val theme = Appearance.theme
        return PillPaint.of(tone, PillLook.of(theme)).words.of(ThemeSet.resolve(theme, dark))
    }

    /** A layer's ink at its alpha (multiplied, as iOS's `opacity`). */
    fun color(layer: PillPaint.Layer, set: ThemeSet): Color {
        val c = layer.ink.of(set)
        return c.copy(alpha = c.alpha * layer.alpha)
    }
}

/** D-220 (L21): a photo takes its card's frame: clipped to [ThemeArt.cardShape] ([radius] holds in Aurora only), then
 *  the theme's edge over it: Norse's 1 dp edge on the four straight sides ([notchEdge], D-226), Vardr Labs' frame marks in white at `frameAlpha` (the
 *  photo's own words are white; `textDim` marks vanish on a dark photo), none in Aurora. Twin of iOS's `photoFrame`.
 *  [dark] is the twin's signature: no edge depends on the mode today. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.photoFrame(radius: Int?, dark: Boolean): Modifier {
    val theme = Appearance.theme
    val shape = ThemeArt.cardShape(theme, radius)
    val clipped = clip(shape)
    return when (CardStyle.of(theme)) {
        CardStyle.GLASS -> clipped
        CardStyle.NOTCHED -> clipped.notchEdge(ThemeArt.color("norseEdge"), ThemeArt.m("chamfer"))
        CardStyle.FRAMED -> clipped.drawWithContent {
            drawContent()
            val mark = Color.White.copy(alpha = ThemeArt.m("frameAlpha"))
            val px = 1.dp.toPx()
            ArtGeometry.frameMarks(size.width, size.height, ThemeArt.m("frameInset").dp.toPx(), ThemeArt.m("frameLength").dp.toPx())
                .forEach { (a, b) -> drawLine(mark, a, b, strokeWidth = px) }
        }
    }
}

/** D-226 (L35, Q18 a): a Norse card's 1 dp edge on its four straight sides only ([ArtGeometry.notchEdges], inset half
 *  the line so it lies inside the shape: D28), clipped to the notched outline at [chamferDp], so the corner cuts are the
 *  Look Book's `clip-path` exactly: no line on the diagonals. Drawn over the content as `border` was (a photo's edge
 *  sits on the photo). The card edge only: controls keep their whole notched outline. The twin of iOS's `NotchEdges`. */
fun Modifier.notchEdge(color: Color, chamferDp: Float): Modifier = drawWithContent {
    drawContent()
    val line = 1.dp.toPx()
    val notch = Path().apply { addOutline(NotchedShape(chamferDp.dp).createOutline(size, layoutDirection, this@drawWithContent)) }
    clipPath(notch) {
        ArtGeometry.notchEdges(size.width, size.height, chamferDp.dp.toPx(), line / 2)
            .forEach { (a, b) -> drawLine(color, a, b, strokeWidth = line) }
    }
}

/** D-243 (T7, the approved Vibrant mock-up, .drafts/u24/norse-vibrant.html): Norse's static gold glow OUTSIDE a card's
 *  notched edge, beneath everything the card draws (it goes first in the card's chain): [ArtGeometry.glowBands]
 *  stroked as concentric outlines `norseEdgeGlowBand` dp wide, each grown from the card's by its band's offset
 *  ([ArtGeometry.grownNotched]), [color] at the band's alpha, drawn outside the bounds (a clip earlier in a card's chain cuts it: LedgerCard draws its own before its ripple's clip).
 *  The band paths are built once per size (drawWithCache), none per frame. No blur and no shadow: the same bands as
 *  iOS's `NorseEdgeGlow`. Nothing inside the card is drawn. */
fun Modifier.norseEdgeGlow(color: Color, chamferDp: Float): Modifier = drawWithCache {
    val chamfer = chamferDp.dp.toPx()
    val bands = ArtGeometry.glowBands()
    val outlines = bands.map { (d, _) -> ArtGeometry.roundedPolygon(ArtGeometry.grownNotched(size.width, size.height, chamfer, d.dp.toPx()), 0f) }
    val band = Stroke(width = ThemeArt.m("norseEdgeGlowBand").dp.toPx(), join = StrokeJoin.Miter)
    onDrawBehind { outlines.forEachIndexed { i, outline -> drawPath(outline, color, alpha = bands[i].second.toFloat(), style = band) } }
}

/** D-220: what [ThemeArt.meterKind] decides. */
enum class MeterKind { CAPSULE, SQUARE }

/** D-220: what [ThemeArt.cardKind] decides, so a test can pin it (twin of iOS's `CardShapeKind`). */
sealed interface CardShapeKind {
    data class Rounded(val dp: Int) : CardShapeKind
    data class Notched(val dp: Float) : CardShapeKind
    object Square : CardShapeKind
}

/** G1: what [ThemeArt.controlKind] decides, so a test can pin it. */
sealed interface ControlShapeKind {
    data class Rounded(val dp: Int) : ControlShapeKind
    data class Notched(val dp: Float) : ControlShapeKind
}

/** D-213: the art's shapes as vertex lists (testable on the JVM); the twin of iOS's ArtGeometry. */
object ArtGeometry {
    fun notched(w: Float, h: Float, chamfer: Float): List<Offset> {
        val c = min(chamfer, min(w, h) / 2)
        return listOf(
            Offset(c, 0f), Offset(w - c, 0f), Offset(w, c), Offset(w, h - c),
            Offset(w - c, h), Offset(c, h), Offset(0f, h - c), Offset(0f, c),
        )
    }

    /** D-243 (T7, .drafts/d214/motion.py `edge_bands`, section 1f): Norse's card-edge glow as concentric bands, the
     *  approved mock-up's CSS drop-shadow (blur 7 = 2 sigma) outside a straight edge. Band k is centred `band (k + 0.5)`
     *  dp out from the edge at `peak Phi(-d / sigma)` = `peak erfc(d / (sigma sqrt 2)) / 2`, until a band would fall
     *  under one 8-bit step (1/255): (offset dp, alpha). 0.18, 3.0 and 0.5 give twelve bands, 6 dp. The twin of iOS's
     *  `ArtGeometry.glowBands`, which calls the C library's erfc; the JVM has none, so [erfc] is ported. */
    fun glowBands(
        peak: Double = ThemeArt.METRICS.getValue("norseEdgeGlow"),
        sigma: Double = ThemeArt.METRICS.getValue("norseEdgeGlowSigma"),
        band: Double = ThemeArt.METRICS.getValue("norseEdgeGlowBand"),
    ): List<Pair<Float, Double>> {
        if (peak <= 0.0 || sigma <= 0.0 || band <= 0.0) return emptyList()
        val out = mutableListOf<Pair<Float, Double>>()
        while (true) {
            val d = band * (out.size + 0.5)
            val a = peak * erfc(d / (sigma * sqrt(2.0))) / 2
            if (a < 1.0 / 255) return out
            out += d.toFloat() to a
        }
    }

    /** D-243 (T7): how far the glow reaches past a card's edge, the bands' outer edge (6 dp). It is also motion.py's
     *  CLEAR DISTANCE: every band fails textDim over a flaring rune, so page-ground text keeps at least this far from a
     *  Norse card (.drafts/d243/gap-audit.md). */
    fun glowReach(): Float = glowBands().size * ThemeArt.m("norseEdgeGlowBand")

    /** The complementary error function, Numerical Recipes' `erfcc` (a Chebyshev fit, fractional error under 1.2e-7
     *  everywhere; 9.4e-8 against Python's math.erfc on 0..4, .drafts/d243/t7/geometry.py): the glow's alphas then
     *  differ from iOS's by under 1e-8. */
    fun erfc(x: Double): Double {
        val z = abs(x)
        val t = 1.0 / (1.0 + 0.5 * z)
        val ans = t * exp(
            -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
                t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))),
        )
        return if (x >= 0) ans else 2.0 - ans
    }

    /** D-243 (T7): the notched outline grown [d] outward with mitred joins: the box [d] larger on every side (from
     *  (-d, -d)) and the chamfer (clamped first, as [notched]'s) `c + d (2 - sqrt 2)`, so every side, the 45 degree cuts
     *  included, runs exactly [d] from the card's own. The twin of iOS's `ArtGeometry.grownNotched`. */
    fun grownNotched(w: Float, h: Float, chamfer: Float, d: Float): List<Offset> {
        val c = min(chamfer, min(w, h) / 2)
        return notched(w + 2 * d, h + 2 * d, c + d * (2 - sqrt(2f))).map { Offset(it.x - d, it.y - d) }
    }

    fun diamond(w: Float, h: Float): List<Offset> = listOf(Offset(w / 2, 0f), Offset(w, h / 2), Offset(w / 2, h), Offset(0f, h / 2))

    fun hexagon(w: Float, h: Float): List<Offset> =
        listOf(0.25f to 0.02f, 0.75f to 0.02f, 1f to 0.5f, 0.75f to 0.98f, 0.25f to 0.98f, 0f to 0.5f).map { Offset(w * it.first, h * it.second) }

    /** D-226 (L35, Q18 a, .drafts/d226/art.py section 52): the Norse card's edge on its four straight sides only (top,
     *  right, bottom, left), as the Look Book's clip-path cuts the card's border at the corners. Each segment runs
     *  [inset] in from its side (the 1 dp line's centre, so the line lies inside the shape: D28) and ends where that
     *  centre line meets the corner's diagonal. The chamfer is clamped to half the short side, as [notched]'s. The twin
     *  of iOS's `ArtGeometry.notchEdges`. */
    fun notchEdges(w: Float, h: Float, chamfer: Float, inset: Float = 0.5f): List<Pair<Offset, Offset>> {
        val c = min(chamfer, min(w, h) / 2)
        val i = inset
        return listOf(
            Offset(c - i, i) to Offset(w - c + i, i),
            Offset(w - i, c - i) to Offset(w - i, h - c + i),
            Offset(w - c + i, h - i) to Offset(c - i, h - i),
            Offset(i, h - c + i) to Offset(i, c - i),
        )
    }

    fun frameMarks(w: Float, h: Float, inset: Float, length: Float): List<Pair<Offset, Offset>> {
        val (x0, y0, x1, y1) = listOf(inset, inset, w - inset, h - inset)
        return listOf(
            Offset(x0, y0) to Offset(x0 + length, y0), Offset(x0, y0) to Offset(x0, y0 + length),
            Offset(x1, y0) to Offset(x1 - length, y0), Offset(x1, y0) to Offset(x1, y0 + length),
            Offset(x1, y1) to Offset(x1 - length, y1), Offset(x1, y1) to Offset(x1, y1 - length),
            Offset(x0, y1) to Offset(x0 + length, y1), Offset(x0, y1) to Offset(x0, y1 - length),
        )
    }

    fun gridDots(w: Float, h: Float, pitch: Float): List<Offset> {
        if (pitch <= 0f) return emptyList()
        val dots = mutableListOf<Offset>()
        var y = pitch / 2
        while (y < h) {
            var x = pitch / 2
            while (x < w) {
                dots += Offset(x, y)
                x += pitch
            }
            y += pitch
        }
        return dots
    }

    /** A closed polygon; with [radius] > 0 each corner is rounded by a curve that starts [radius] along each edge. */
    fun roundedPolygon(p: List<Offset>, radius: Float): Path {
        val path = Path()
        if (p.size < 3) return path
        fun toward(a: Offset, b: Offset): Offset {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1e-6f)
            val t = min(radius, len / 2) / len
            return Offset(a.x + dx * t, a.y + dy * t)
        }
        p.indices.forEach { i ->
            val v = p[i]
            val a = toward(v, p[(i + p.size - 1) % p.size])
            val b = toward(v, p[(i + 1) % p.size])
            if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
            if (radius > 0f) path.quadraticTo(v.x, v.y, b.x, b.y) else path.lineTo(b.x, b.y)
        }
        path.close()
        return path
    }
}

/** D-226 (L31, .drafts/d226/art.py section 49): a CSS `linear-gradient(<degrees>deg, ...)` on a [width] x [height] box
 *  as unit points (fractions of the box; multiply by its size for a Brush). CSS's direction is (sin, -cos) of the angle
 *  and its gradient line is |w sin| + |h cos| long through the centre, so the ends can lie outside the box. 150 on
 *  72 x 64 gives C1's bolt points. An empty box gives the centre twice. The twin of iOS's `CssGradient`. */
object CssGradient {
    fun unitPoints(degrees: Double, width: Double, height: Double): Pair<Offset, Offset> {
        if (width <= 0.0 || height <= 0.0) return Offset(0.5f, 0.5f) to Offset(0.5f, 0.5f)
        val r = Math.toRadians(degrees)
        val dx = sin(r)
        val dy = -cos(r)
        val half = (abs(width * dx) + abs(height * dy)) / 2
        return Offset((0.5 - dx * half / width).toFloat(), (0.5 - dy * half / height).toFloat()) to
            Offset((0.5 + dx * half / width).toFloat(), (0.5 + dy * half / height).toFloat())
    }
}

/** D-226 (L36, .drafts/d226/art.py section 50): Compose's shadow radius goes to Skia's BlurMaskFilter, whose sigma is
 *  0.57735 x radius + 0.5 px; iOS's shadow radius is taken as the sigma (the CSS blur / 2). So the radius in dp that
 *  draws a [sigmaDp] blur at [density] px per dp; never below 0. */
object ShadowBlur {
    fun radiusDp(sigmaDp: Float, density: Float): Float = ((sigmaDp * density - 0.5f) / 0.57735f / density).coerceAtLeast(0f)
}

/** D-226 (L36, A1, .drafts/d226/art.py sections 51 and 53): Aurora's glass card on Android, from the one table: the
 *  surface film over the blurred page art ([film] with `blurs`, iOS's 0.78) or over no blur (API 26 to 30, or no art
 *  recorded: 0.82), and the white rim that matches iOS's measured edge in light and dark. */
object GlassLook {
    fun film(blurs: Boolean): Float = ThemeArt.m(if (blurs) "glassFilm" else "glassFilmNoBlur")
    fun rim(dark: Boolean): Float = ThemeArt.m(if (dark) "glassRimDark" else "glassRimLight")

    /** Fix wave (F3, .drafts/d226/art.py section 55): Compose's dropShadow at iOS's sigma, y and alpha read 0.58 of
     *  iOS's depth on the look (luma under the card -9.0 / -7.0 / -3.0 against -15.5 / -11.8 / -5.2 at 1, 6 and 14;
     *  the falloff the same), so Android draws the table's alpha times the measured ratio. Android only. */
    const val SHADOW_GAIN = 1.71f

    fun shadowAlpha(): Float = ThemeArt.m("glassShadowAlpha") * SHADOW_GAIN

    /** Fix wave (F4, art.py 55): iOS's material lifts the dark card above `surface` (#131C26 against Android's #0D1822
     *  over the same ground; no film can, a > 1), so the dark card draws white at this over its film. Light none. */
    const val LIFT_DARK = 0.02f

    fun lift(dark: Boolean): Float = if (dark) LIFT_DARK else 0f
}

class NotchedShape(private val chamfer: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(ArtGeometry.roundedPolygon(ArtGeometry.notched(size.width, size.height, with(density) { chamfer.toPx() }), 0f))
}

/** G1 fix 1: Norse's notched rectangle at the chamfer [ThemeArt.panelChamfer] gives for its own height. */
object PanelShape : Shape {
    fun chamferDp(heightPx: Float, density: Density): Float = ThemeArt.panelChamfer(heightPx / density.density)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        NotchedShape(chamferDp(size.height, density).dp).createOutline(size, layoutDirection, density)
}

class DiamondShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(ArtGeometry.roundedPolygon(ArtGeometry.diamond(size.width, size.height), with(density) { radius.toPx() }))
}

object HexagonShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(ArtGeometry.roundedPolygon(ArtGeometry.hexagon(size.width, size.height), 0f))
}

enum class TabIndicator {
    PILL, UNDERLINE, OUTLINE;

    companion object {
        fun of(theme: AppTheme) = when (theme) {
            AppTheme.AURORA -> PILL
            AppTheme.NORSE -> UNDERLINE
            AppTheme.VARDR -> OUTLINE
        }

        fun drawsRunes(theme: AppTheme) = theme == AppTheme.NORSE

        /** D-219 (L13): the active tab's glyph colour: `onAccent` in Aurora (its pill is filled accent), `accent` in Norse,
         *  `ring` in Vardr Labs. */
        fun activeGlyph(theme: AppTheme): TabGlyphTint = when (theme) {
            AppTheme.AURORA -> TabGlyphTint.ON_ACCENT
            AppTheme.NORSE -> TabGlyphTint.ACCENT
            AppTheme.VARDR -> TabGlyphTint.RING
        }

        /** The Look Book's 0.50, nudged to 0.75 in Vardr Labs light (art.py: 3:1 on the bar). */
        fun outlineAlpha(set: ThemeSet): Float = ThemeArt.m(if (set == ThemeSet.VARDR_LIGHT) "outlineLight" else "outlineDark")
    }
}

/** D-219 (L13): which token the active tab's glyph is drawn in; the twin of iOS's `TabGlyphTint`. */
enum class TabGlyphTint { ON_ACCENT, ACCENT, RING }

/** D-219 (L15): the tab bar's ground; the twin of iOS's `TabBarGround`. Aurora's is glass (D-220: the page blurred
 *  under a film from API 31, the film alone below it); Norse and Vardr Labs are the solid `surface` token. A 1 dp `hairline` rule runs along the bar's top in every theme. */
enum class TabBarGround {
    GLASS, SOLID;

    companion object {
        fun of(theme: AppTheme): TabBarGround = if (theme == AppTheme.AURORA) GLASS else SOLID

        /** Android's glass film: `surface` at this alpha (iOS's is 0.72 over a material blur: its own constant). D-220:
         *  the film of a bar that cannot blur (API 26 to 30, or a bar drawn without a page under it). */
        const val FILM_ALPHA = 0.94f

        /** D-220 (L-D217-3): the film over the blur, iOS's `TabBarGround.filmAlpha` (a test reads it from ThemeArt.swift). */
        const val BLUR_FILM_ALPHA = 0.72f

        /** D-220: the blur's radius, dp (decided at the look, side by side with iOS). */
        const val BLUR_RADIUS = 20f

        /** D-220: RenderEffect, and so the blur, exists from API 31. */
        fun blurs(sdkInt: Int): Boolean = sdkInt >= 31

        /** D-220: the alpha of the surface a bar's ground draws: solid themes 1; Aurora's glass 0.72 over a blur, or
         *  C1's 0.94 where it does not blur. */
        fun film(theme: AppTheme, blurs: Boolean): Float = when {
            of(theme) == SOLID -> 1f
            blurs -> BLUR_FILM_ALPHA
            else -> FILM_ALPHA
        }
    }
}

/** D-219 (L17): the raised +'s footprint, the twin of iOS's `AddButtonGeometry`: every face is laid out in one
 *  [FOOTPRINT] dp square and drawn centred on it (the diamond overflows); [LIFT] dp up. [faceSize] is each face's own
 *  box in dp, (width, height). */
object AddButtonGeometry {
    const val FOOTPRINT = 64f
    const val LIFT = 18f

    fun faceSize(art: AddButtonArt): Pair<Float, Float> = when (art) {
        AddButtonArt.CIRCLE -> FOOTPRINT to FOOTPRINT
        AddButtonArt.DIAMOND -> (ThemeArt.m("diamondSide") * kotlin.math.sqrt(2f)).let { it to it }
        AddButtonArt.BOLT -> ThemeArt.m("boltWidth") to ThemeArt.m("boltHeight")
    }

    /** D-219 fix wave (look defect 2): the diamond's ring in dp, as iOS draws it, `stroke(Tokens.bg, lineWidth: 4)`: a
     *  stroke CENTRED on the rounded path, so half of it lies outside the face. (A `border` is drawn inside the shape and
     *  made Android's diamond 4 dp smaller across than iOS's.) */
    const val DIAMOND_RING = 4f

    /** The diamond's outer extent in dp, ring included, across either axis: the rounded tip retreats from the box's edge
     *  by a quarter of the corner's tangent length times root two (a quadratic curve's apex is the average of its
     *  two ends and its control point), and the centred ring adds [DIAMOND_RING] in all (half each side). */
    fun diamondOuterExtent(): Float {
        val box = faceSize(AddButtonArt.DIAMOND).first
        val radius = ThemeArt.m("diamondRadius")
        return box - radius * kotlin.math.sqrt(2f) / 2f + DIAMOND_RING
    }
}

/** D-219 (L16): the + circle's shadow is neutral (default black shadow colours, no `spotColor`); iOS's twin is black at
 *  0.2, radius 7, y 6. The bolt keeps none. */
object AddButtonShadow {
    const val ELEVATION = 7f
}

/** D-219 (L14): the Vardr Labs bolt's glyph, the twin of iOS's `ReticleGlyph` (ThemeArtTest compares the two files):
 *  the arms and the filled centre dot as path data, 24-unit viewport. */
object ReticleGlyph {
    const val ARMS = "M3.5 12h5.5M15 12h5.5M12 3.5V9M12 15v5.5"
    const val DOT = "M10.7 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0"
}

/** D-219 (L14): SKIPTI's own drawings (not Lucide's; no NOTICE entry). [Reticle] is the Vardr Labs bolt's glyph, the
 *  Look Book's reticle: the arms stroked at [LucideStroke.WIDTH] with round caps and joins, the centre dot filled; the
 *  path data is [ReticleGlyph]'s, the same strings as iOS's. 24 x 24 viewport, tinted by `Icon`. */
object ArtIcons {
    val Reticle: ImageVector by lazy {
        ImageVector.Builder(
            name = "reticle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
            .addPath(
                pathData = PathParser().parsePathString(ReticleGlyph.ARMS).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = LucideStroke.WIDTH,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .addPath(
                pathData = PathParser().parsePathString(ReticleGlyph.DOT).toNodes(),
                fill = SolidColor(Color.Black),
                stroke = null,
            )
            .build()
    }
}

enum class AddButtonArt {
    CIRCLE, DIAMOND, BOLT;

    companion object {
        fun of(theme: AppTheme) = when (theme) {
            AppTheme.AURORA -> CIRCLE
            AppTheme.NORSE -> DIAMOND
            AppTheme.VARDR -> BOLT
        }
    }
}

enum class CardStyle {
    GLASS, NOTCHED, FRAMED;

    companion object {
        fun of(theme: AppTheme) = when (theme) {
            AppTheme.AURORA -> GLASS
            AppTheme.NORSE -> NOTCHED
            AppTheme.VARDR -> FRAMED
        }
    }
}

/** A group's tile (`person = false`) or a person's initial on a tone (`person = true`). */
enum class TileShape {
    ROUNDED, CIRCLE, DIAMOND, SQUARE;

    companion object {
        fun of(theme: AppTheme, person: Boolean) = when (theme) {
            AppTheme.AURORA -> if (person) CIRCLE else ROUNDED
            AppTheme.NORSE -> DIAMOND
            AppTheme.VARDR -> SQUARE
        }
    }
}

enum class ButtonLook {
    FILLED, OUTLINED;

    companion object {
        /** A disabled button is drawn at this opacity on both phones (D-220; iOS's `ButtonLook.disabledAlpha`). */
        const val DISABLED_ALPHA = 0.45f

        fun of(theme: AppTheme) = if (theme == AppTheme.VARDR) OUTLINED else FILLED

        /** The words a button draws: Vardr Labs' outlined look uppercases them (the phone's language), unless
         *  [keepsCase] (an identifier such as an @username suggestion); the other themes keep the text. */
        fun label(text: String, theme: AppTheme, keepsCase: Boolean = false): String =
            if (of(theme) == OUTLINED && !keepsCase) text.uppercase(java.util.Locale.getDefault()) else text
    }
}

enum class Ground {
    CURTAINS, RUNES, GRID;

    companion object {
        fun of(theme: AppTheme) = when (theme) {
            AppTheme.AURORA -> CURTAINS
            AppTheme.NORSE -> RUNES
            AppTheme.VARDR -> GRID
        }
    }
}

object SectionMark {
    /** What goes before a section label: ᛟ and a space in Norse, nothing elsewhere. */
    fun prefix(theme: AppTheme): String? = if (theme == AppTheme.NORSE) "${Runes.SECTION_MARK} " else null
}

/** D-213: a SECTION label (it names what follows): ᛟ before it in Norse, in the accent. Status words, hints and
 *  row flags keep `Type.kicker` (the plan's rule). */
object SectionLabels {
    fun annotated(text: String, theme: AppTheme = Appearance.theme): AnnotatedString = buildAnnotatedString {
        SectionMark.prefix(theme)?.let { withStyle(SpanStyle(color = Tok.accent.norse, letterSpacing = 0.sp)) { appendWithRunes(it) } }
        // A rune inside the words (a currency heading with a separator) takes the rune font, not the accent.
        appendWithRunes(if (theme == AppTheme.VARDR) text.uppercase(java.util.Locale.getDefault()) else text)
    }
}

/** A section label in the micro face; TalkBack reads the words without the mark. */
@Composable
fun SectionLabel(text: String, dark: Boolean, modifier: Modifier = Modifier, color: Color = Tok.textDim(dark)) {
    val marked = SectionMark.prefix(Appearance.theme) != null
    Text(
        SectionLabels.annotated(text),
        style = Type.microLabel(dark).copy(color = color),
        modifier = if (marked) modifier.semantics { contentDescription = text } else modifier,
    )
}

/** F2: a rune drawn as art keeps its size at every text size, as on iOS (the Text size card's fixed "A"s use
 *  the same idiom): the metric in dp, turned into the sp that draws exactly that many dp at [density]. */
fun runeSize(metric: String, density: Density): TextUnit = with(density) { ThemeArt.m(metric).dp.toSp() }

object TitleOrnaments {
    fun shows(theme: AppTheme) = theme == AppTheme.NORSE

    /** D-217 (the Look Book's `.orn`): one rule's share of the width; the rune takes the rest (16%). Twin of iOS's. */
    const val RULE_FRACTION = 0.42f
    /** dp between the large title's text and the ornament's rules. */
    const val GAP_UNDER_TITLE = 8
    /** The row's height: it holds the 17 dp rune (D-226, art.py section 46: Vardr Runes Carved's 1.15 em line box,
     *  19.55, rounded up to even; the same rule gave 16 for the 13 dp rune). Twin of iOS's `rowHeight`. */
    const val ROW_HEIGHT = 20

    fun ruleWidth(width: Float): Float = width * RULE_FRACTION

    /** iOS's `titleBottomInset`: pt from the large title's text to the top of a tab root's scroll content. */
    const val IOS_TITLE_BOTTOM_INSET = 15

    /** D-223 fix wave (F5): dp to add to a tab root's content top in Norse, so the content hands over from the ornament
     *  as on iOS. iOS draws the ornament inside its scroll content ([iosAbove] the content's own top padding) with a
     *  negative top padding, and a padded frame never goes below zero height: its first content starts
     *  max(inset + above, gap + row) under the title's text, then [iosSpacing] (the stack's spacing). The scaffold
     *  puts it gap + row + (above - TAB_ROOT_BASE_PADDING) under the title's baseline, then [BarDecisions.CONTENT_SPACING].
     *  0 for Groups (above 8); -5 for Balance, Expenses and Settle up (above 16); -7 for Balance's empty state (its
     *  VStack's spacing is 12). (D-226: the row grew 16 to 20, so iOS's max no longer takes the row above 8: -1 and
     *  -3 before.) */
    fun contentShift(iosAbove: Int, iosSpacing: Int = BarDecisions.CONTENT_SPACING): Int {
        val ios = maxOf(IOS_TITLE_BOTTOM_INSET + iosAbove, GAP_UNDER_TITLE + ROW_HEIGHT) + iosSpacing
        val scaffold = GAP_UNDER_TITLE + ROW_HEIGHT + (iosAbove - BarDecisions.TAB_ROOT_BASE_PADDING) + BarDecisions.CONTENT_SPACING
        return ios - scaffold
    }

    /** The top offset (dp) that puts the row [GAP_UNDER_TITLE] under the title's BASELINE (iOS's 15.7 pt from the
     *  large title's text to the rule was measured on the glyph bottom), for a column that already puts [above] dp
     *  between the title's box and the ornament and whose title box ends [belowBaseline] dp under its baseline. iOS
     *  subtracts the system's measured title inset (`titleBottomInset`, 15) in the same place; here the scaffold
     *  reads the title's own baseline. */
    fun topOffset(above: Int, belowBaseline: Int = 0): Int = GAP_UNDER_TITLE - above - belowBaseline
}

/** D-213 (the founder, demo option A): a gold rule, ᛜ, a silver rule, under the title of the four tab roots, in Norse
 *  only (D-217: not on You, a pushed page); decorative, so TalkBack skips it. The twin of iOS's TitleOrnament.
 *  D-217: each rule is 42% of the width, the rune sits in the 16% between them, and the row hangs
 *  [TitleOrnaments.GAP_UNDER_TITLE] under the title's baseline: [above] is the dp the parent column already puts over
 *  it, [belowBaseline] the dp the title's own box extends under its baseline (TabRootScaffold reads both). */
@Composable
fun TitleOrnament(dark: Boolean, above: Int, belowBaseline: Int = 0, modifier: Modifier = Modifier) {
    if (!TitleOrnaments.shows(Appearance.theme)) return
    val density = LocalDensity.current
    Row(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val up = with(density) { TitleOrnaments.topOffset(above, belowBaseline).dp.roundToPx() }
                layout(placeable.width, (placeable.height + up).coerceAtLeast(0)) { placeable.place(0, up) }
            }
            .height(TitleOrnaments.ROW_HEIGHT.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(TitleOrnaments.RULE_FRACTION).height(1.dp).background(Tok.accent(dark)))
        Box(Modifier.weight(1f - 2 * TitleOrnaments.RULE_FRACTION), contentAlignment = Alignment.Center) {
            Text(Runes.ORNAMENT, style = Type.rune(runeSize("ornamentRune", LocalDensity.current)).copy(color = Tok.accent(dark)))
        }
        Box(Modifier.weight(TitleOrnaments.RULE_FRACTION).height(1.dp).background(ThemeArt.color("norseSilver")))
    }
}

/** D-221 (Q2 a, K05, K06): an initials avatar's colours, the Look Book's: [SOFT] the accent's soft fill with the
 *  initials (or emoji, or glyph) in the accent; [ACCENT] (Home's account badge) the accent fill with `onAccent`. A
 *  photo covers the fill. The twin of iOS's `AvatarFill`. */
enum class AvatarFill(val ground: Tok, val ink: Tok) {
    SOFT(Tok.accentSoft, Tok.accent),
    ACCENT(Tok.accent, Tok.onAccent),
}

/** D-221 (L44): the milestone's seal is the theme's tile: Aurora a circle, Norse the diamond (with its gold-brown edge,
 *  [tileEdge]), Vardr Labs a square. The twin of iOS's `SealStyle`. */
object SealStyle {
    fun clip(theme: AppTheme): TileShape = when (theme) {
        AppTheme.AURORA -> TileShape.CIRCLE
        AppTheme.NORSE -> TileShape.DIAMOND
        AppTheme.VARDR -> TileShape.SQUARE
    }
}

/** D-221 (L40): You's "n of m done" pill; Vardr Labs alone leads it with a dot in `ring` (the Look Book's H29 dot),
 *  [ThemeArt] `progressDot` wide and `progressDotGap` from the words. The twin of iOS's `ProgressPill.showsDot`. */
object ProgressPill {
    fun showsDot(theme: AppTheme): Boolean = theme == AppTheme.VARDR
}

object AvatarStyle {
    /** D-221: Home's account badge takes the accent fill; every other avatar the soft one. */
    fun fill(badge: Boolean): AvatarFill = if (badge) AvatarFill.ACCENT else AvatarFill.SOFT

    /** D-221 (K06): Norse edges an avatar in its gold-brown, 1 dp; the others draw no edge (the hairline is gone). */
    fun edgeHex(theme: AppTheme): String? = if (theme == AppTheme.NORSE) ThemeArt.HEX.getValue("norseEdge") else null

    /** D-217: an initials avatar's letter is [TypeScale.avatarInitial] of the avatar. D-221 (K05): in the display face
     *  at 700 in every theme (Norse's Cormorant SC, the others' display face), as iOS's `AvatarView`. */
    fun initialStyle(dark: Boolean, size: Float): TextStyle {
        val letter = (size * TypeScale.avatarInitial).sp
        return Type.display(dark, 700).copy(fontSize = letter)
    }
}

/** D-213: the meta separator, the twin of iOS's `Separator`. Notifications and exported files keep the middle dot. */
object Separator {
    fun meta(theme: AppTheme = Appearance.theme): String = if (theme == AppTheme.NORSE) " ${Runes.SEPARATOR} " else " \u00B7 "

    /** A meta line whose separators take the accent in Norse (the whole " rune ", spaces included, as on iOS); plain
     *  elsewhere. D-215: every rune in it, also one inside a part (a category label), is drawn in the rune font. */
    fun annotated(parts: List<String>, theme: AppTheme = Appearance.theme): AnnotatedString = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            if (i > 0) {
                if (theme == AppTheme.NORSE) withStyle(SpanStyle(color = Tok.accent.norse)) { appendWithRunes(meta(theme)) } else append(meta(theme))
            }
            appendWithRunes(part)
        }
    }
}

/** The clip a tile takes for a [TileShape]. */
fun tileShape(shape: TileShape, size: Int): Shape = when (shape) {
    TileShape.ROUNDED -> RoundedCornerShape((size * 0.3f).dp)
    TileShape.CIRCLE -> CircleShape
    TileShape.DIAMOND -> DiamondShape(ThemeArt.m("tileRadius").dp)
    TileShape.SQUARE -> RectangleShape
}

/** D-220 (L23): a diamond is drawn at `size * sqrt(2)`, centred on the tile's `size` footprint (it overflows by 0.207 x
 *  size a side; art.py section 15); every other shape at `size`. Twin of iOS's `TileClip.drawnSide`. */
fun tileDrawnSide(shape: TileShape, size: Int): Float = if (shape == TileShape.DIAMOND) size * kotlin.math.sqrt(2f) else size.toFloat()

/** The initial's face on a tile: one factor, 0.42 of the size, on every shape (D-220: the diamond's inscribed square is
 *  now 0.707 of the size); the twin of iOS's `TileClip.initialFont`. */
fun tileInitialStyle(shape: TileShape, size: Int, dark: Boolean, color: Color): TextStyle =
    Type.display(dark, 700).copy(fontSize = (size * 0.42f).sp, color = color)

/** D-222 A2: the width of Norse's tile edge, iOS's `lineWidth: 1` in `TileClip.edge`. */
object TileEdge {
    const val WIDTH = 1f
}

/** Norse's diamond carries the gold-brown edge; the other shapes none (twin of iOS's `TileClip.edge`). D-222 A2: the
 *  edge is a stroke CENTRED on the diamond, drawn over the content, as iOS's overlay strokes it (half lies outside the
 *  path); callers place it BEFORE their clip so the outer half is not cut. */
fun Modifier.tileEdge(shape: TileShape, size: Int): Modifier =
    if (shape == TileShape.DIAMOND) drawWithContent {
        drawContent()
        val path = ArtGeometry.roundedPolygon(ArtGeometry.diamond(this.size.width, this.size.height), ThemeArt.m("tileRadius").dp.toPx())
        drawPath(path, ThemeArt.color("norseEdge"), style = Stroke(width = TileEdge.WIDTH.dp.toPx()))
    } else this

/** The raised +'s face per theme; the twin of iOS's AddButtonFace. The glyph is the set's `onAccent`: Lucide's plus at 28 dp on
 *  the circle and the diamond, the reticle at `reticleGlyph` on the bolt (D-219).
 *  It takes the click itself (F1): `clickable` comes AFTER the shape's clip (the circle's and the diamond's
 *  `shadow`, the bolt's `clip(HexagonShape)`), so the ripple and the touch area follow the shape. [modifier]
 *  is the caller's placement and press scale only. */
@Composable
fun AddButtonFace(
    art: AddButtonArt,
    dark: Boolean,
    interactionSource: MutableInteractionSource,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val plus: @Composable () -> Unit = {
        Icon(imageVector = TabIcons.Plus, contentDescription = null, tint = Tok.onAccent(dark), modifier = Modifier.size(28.dp))
    }
    val click = Modifier
        .clickable(
            interactionSource = interactionSource,
            // D-234 (L12-O1, decision 97): no press film: iOS's + has none, and Material's ripple faded in even under
            // Less motion; the press is the scale and the turn (TabBar.kt), both behind the motion door.
            indication = null,
            role = Role.Button,
            onClickLabel = label,
            onClick = onClick,
        )
        .semantics { contentDescription = label }
    // D-219 (L17): every face sits in the one footprint box and is drawn centred on it at its own size (`requiredSize`:
    // the slot's constraints must not squeeze it, as `size` squeezed the old 70.71 diamond to 69.6 on a 360 dp phone).
    Box(modifier.size(AddButtonGeometry.FOOTPRINT.dp), contentAlignment = Alignment.Center) {
        when (art) {
            AddButtonArt.CIRCLE -> {
                val (w, h) = AddButtonGeometry.faceSize(AddButtonArt.CIRCLE)
                Box(
                    Modifier
                        .requiredSize(w.dp, h.dp)
                        .shadow(AddButtonShadow.ELEVATION.dp, CircleShape)
                        .background(Tok.accent(dark), CircleShape)
                        .border(4.dp, Tok.bg(dark), CircleShape)
                        .then(click),
                    contentAlignment = Alignment.Center,
                ) { plus() }
            }
            AddButtonArt.DIAMOND -> {
                val shape = DiamondShape(ThemeArt.m("diamondRadius").dp)
                val (w, h) = AddButtonGeometry.faceSize(AddButtonArt.DIAMOND)
                Box(
                    Modifier
                        .requiredSize(w.dp, h.dp)
                        .shadow(AddButtonShadow.ELEVATION.dp, shape, clip = false)
                        // The face and a ring CENTRED on its edge, as iOS draws it (half the ring lies outside the path);
                        // the clip after the drawing is the tap's shape (the ring's outer half is not a tap).
                        .drawBehind {
                            val path = ArtGeometry.roundedPolygon(
                                ArtGeometry.diamond(size.width, size.height), ThemeArt.m("diamondRadius").dp.toPx(),
                            )
                            drawPath(path, Brush.verticalGradient(listOf(ThemeArt.color("diamondTop"), ThemeArt.color("diamondBottom"))))
                            drawPath(path, Tok.bg(dark), style = Stroke(width = AddButtonGeometry.DIAMOND_RING.dp.toPx()))
                        }
                        .clip(shape)
                        .then(click),
                    contentAlignment = Alignment.Center,
                ) { plus() }
            }
            AddButtonArt.BOLT -> {
                val (w, h) = AddButtonGeometry.faceSize(AddButtonArt.BOLT)
                val stops = ThemeArt.boltStops(dark).toTypedArray()
                Box(
                    Modifier
                        .requiredSize(w.dp, h.dp)
                        .clip(HexagonShape)
                        .drawBehind {
                            val brush = Brush.linearGradient(
                                *stops,
                                start = Offset(size.width * ThemeArt.m("boltStartX"), size.height * ThemeArt.m("boltStartY")),
                                end = Offset(size.width * ThemeArt.m("boltEndX"), size.height * ThemeArt.m("boltEndY")),
                            )
                            drawPath(ArtGeometry.roundedPolygon(ArtGeometry.hexagon(size.width, size.height), 0f), brush)
                            drawCircle(Color.White.copy(alpha = ThemeArt.reticleAlpha(dark)), radius = size.height / 3, style = Stroke(1.dp.toPx()))
                        }
                        .then(click),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ArtIcons.Reticle,
                        contentDescription = null,
                        tint = Tok.onAccent(dark),
                        modifier = Modifier.requiredSize(ThemeArt.m("reticleGlyph").dp),
                    )
                }
            }
        }
    }
}
