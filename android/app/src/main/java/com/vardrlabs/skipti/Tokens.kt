package com.vardrlabs.skipti

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private fun c(v: Long) = Color(v)

/**
 * D-212 (ADR-0041, the founder, 2026-10-14): five token sets, the twin of
 * iOS's `Tokens.palette`, from the approved Look Book v2.1. Columns, in
 * order: Aurora light, Aurora dark, Norse (always dark), Vardr Labs light,
 * Vardr Labs dark. AppearanceTest measures every floor in every set; values
 * from .drafts/d212/palette.py. `owed` is what you owe, `owing` what you are
 * owed; the sign and the words are always printed beside them.
 *
 * Cross-platform identity (founder constraint): the 11.4 balance line and
 * tabular figures, NOT materials. ADR-0023 bounds Liquid Glass to iOS system
 * chrome; Android gets no invented equivalent.
 */
enum class Tok(
    val auroraLight: Color, val auroraDark: Color, val norse: Color, val vardrLight: Color, val vardrDark: Color,
) {
    bg(c(0xFFEEF5F3), c(0xFF050A12), c(0xFF07080E), c(0xFFE8ECEF), c(0xFF0B0F13)),
    surface(c(0xFFFFFFFF), c(0xFF101A24), c(0xFF111218), c(0xFFEEF1F4), c(0xFF0E141A)),
    surfaceRaised(c(0xFFF6FAF9), c(0xFF1D2730), c(0xFF1F1F23), c(0xFFEBEEF2), c(0xFF1B2127)),
    hairline(c(0xFFD5E3DF), c(0xFF1D2C38), c(0xFF4A3B1B), c(0xFFC3CDD4), c(0xFF1D2731)),
    text(c(0xFF0E1B1A), c(0xFFE6F5F0), c(0xFFF3EAD3), c(0xFF182430), c(0xFFE9EEF3)),
    textDim(c(0xFF4D615E), c(0xFF8FA9A4), c(0xFFB5A888), c(0xFF46555F), c(0xFF8FA0AE)),
    textFaint(c(0xFF607370), c(0xFF7A918E), c(0xFF9A8F76), c(0xFF5E6C75), c(0xFF7A8995)),
    owed(c(0xFF842D2A), c(0xFFEC7D79), c(0xFFEE8A58), c(0xFF9A4D16), c(0xFFE89055)),
    owing(c(0xFF356F2F), c(0xFF97C87E), c(0xFF7FD4C1), c(0xFF0A6C92), c(0xFF5AD0F2)),
    neutral(c(0xFF607370), c(0xFF7A918E), c(0xFF9A8F76), c(0xFF5E6C75), c(0xFF7A8995)),
    watch(c(0xFF7A611B), c(0xFFE8C170), c(0xFFE8C170), c(0xFF7A611B), c(0xFFE8C170)),
    accent(c(0xFF0B7A5F), c(0xFF45E6B0), c(0xFFDFB043), c(0xFF4C6172), c(0xFF9DB0BF)),
    /** D-214 (the founder, 2026-10-15): faint and accent text sitting directly on the page ground, over the moving
     *  art; in Aurora light the label colour, in Aurora dark the label colour and the accent; in Norse the label colour
     *  and the accent (D-226: its page glows, .drafts/d226/art.py section 48); in Vardr Labs textFaint and accent. The
     *  twin of iOS's `Tokens.groundFaint` and `groundAccent`. */
    groundFaint(c(0xFF4D615E), c(0xFF8FA9A4), c(0xFFB5A888), c(0xFF5E6C75), c(0xFF7A8995)),
    groundAccent(c(0xFF4D615E), c(0xFF45E6B0), c(0xFFDFB043), c(0xFF475B6B), c(0xFF9DB0BF)),
    onAccent(c(0xFFFFFFFF), c(0xFF04140F), c(0xFF130D02), c(0xFFEEF1F4), c(0xFF0B0F13)),
    accentSoft(c(0xFFDDF2EA), c(0xFF10302A), c(0xFF2A2211), c(0xFFDFE5E9), c(0xFF121A21)),
    owingSoft(c(0xFFEBF1EA), c(0xFF283934), c(0xFF253536), c(0xFFD7E4EA), c(0xFF1C3641)),
    owedSoft(c(0xFFF3EAEA), c(0xFF382C33), c(0xFF392824), c(0xFFE6E1DE), c(0xFF352A25)),
    ring(c(0xFF0C8A6C), c(0xFF45E6B0), c(0xFFDFB043), c(0xFF0A6D93), c(0xFF5AD0F2)),
    ringTrack(c(0xFFD5E3DF), c(0xFF1D2C38), c(0xFF2B2A2E), c(0xFFC3CDD4), c(0xFF1D2731)),
    meterStart(c(0xFF0C8A6C), c(0xFF1FB6C9), c(0xFF9AA2AE), c(0xFF4C6172), c(0xFF9DB0BF)),
    meterEnd(c(0xFF2BC9A0), c(0xFF45E6B0), c(0xFFDFB043), c(0xFF0A6D93), c(0xFF5AD0F2)),
    heroStart(c(0xFF03070D), c(0xFF03070D), c(0xFF211A0A), c(0xFFEEF1F4), c(0xFF0B0F13)),
    heroMid(c(0xFF071321), c(0xFF071321), c(0xFF16120C), c(0xFFEEF1F4), c(0xFF0B0F13)),
    heroEnd(c(0xFF0A1B26), c(0xFF0A1B26), c(0xFF0A0A0F), c(0xFFEEF1F4), c(0xFF0B0F13)),
    onHero(c(0xFFEAFBF4), c(0xFFEAFBF4), c(0xFFF3EAD3), c(0xFF182430), c(0xFFE9EEF3));

    fun of(set: ThemeSet): Color = when (set) {
        ThemeSet.AURORA_LIGHT -> auroraLight
        ThemeSet.AURORA_DARK -> auroraDark
        ThemeSet.NORSE -> norse
        ThemeSet.VARDR_LIGHT -> vardrLight
        ThemeSet.VARDR_DARK -> vardrDark
    }

    /** `Appearance.theme` is snapshot state, so reading it here recomposes
     *  every token user on a theme change. */
    operator fun invoke(dark: Boolean): Color = of(ThemeSet.resolve(Appearance.theme, dark))
}

/** Typography roles (11.3's four roles on the bundled TTFs). */
/**
 * D-165: every choice chip's colours, in one place. A selected chip's label
 * is the page colour on the accent fill, the pair D-144 pinned at 4.5:1 for
 * button labels. Add, New group and their split and payer rows drew Tok.text
 * there (2.44:1 light, 1.91:1 dark, 1.61:1 Night; seen on the emulator).
 * AppearanceTest checks that no screen builds chip colours of its own.
 */
@androidx.compose.runtime.Composable
fun chipColors(dark: Boolean): androidx.compose.material3.SelectableChipColors =
    androidx.compose.material3.FilterChipDefaults.filterChipColors(
        containerColor = Tok.surface(dark),
        labelColor = Tok.textDim(dark),
        selectedContainerColor = Tok.accent(dark),
        selectedLabelColor = Tok.onAccent(dark),
    )

/** D-212: every Switch's colours, in one place. The unchecked thumb and border are dim text on the raised surface
 *  (about 6:1 in every set); Material's own take `outline`, which is textFaint since fix wave 3 (a field's border, 3:1). */
@androidx.compose.runtime.Composable
fun switchColors(dark: Boolean): androidx.compose.material3.SwitchColors {
    val p = switchPalette(ThemeSet.resolve(Appearance.theme, dark))
    return androidx.compose.material3.SwitchDefaults.colors(
        checkedTrackColor = p.checkedTrack,
        checkedThumbColor = p.checkedThumb,
        uncheckedThumbColor = p.uncheckedThumb,
        uncheckedBorderColor = p.uncheckedBorder,
        uncheckedTrackColor = p.uncheckedTrack,
    )
}

/** The five colours [switchColors] draws, as plain values (a JVM test measures them). */
data class SwitchPalette(
    val checkedTrack: Color,
    val checkedThumb: Color,
    val uncheckedThumb: Color,
    val uncheckedBorder: Color,
    val uncheckedTrack: Color,
)

fun switchPalette(set: ThemeSet): SwitchPalette = SwitchPalette(
    checkedTrack = Tok.accent.of(set),
    checkedThumb = Tok.bg.of(set),
    uncheckedThumb = Tok.textDim.of(set),
    uncheckedBorder = Tok.textDim.of(set),
    uncheckedTrack = Tok.surfaceRaised.of(set),
)

/** D-212 (ADR-0041): the faces, and the roles that pick one per theme. */
enum class Face { GROTESK, SANS, CORMORANT, PLEX }
enum class Role { DISPLAY, BODY, NUMERIC, LABEL, MICRO }

/**
 * D-217 (the founder, 2026-10-16: both phones draw the same sizes, and where they differed iOS's value is the scale):
 * every size a screen takes from the scale, in sp here and points on iOS. The names and values are read against iOS's
 * `enum TypeScale` (TypeSystem.swift) by TypeThemeTest, one by one, so the two tables cannot drift. [avatarInitial]
 * is a fraction of the avatar's own size, not an sp value.
 */
object TypeScale {
    const val largeTitle = 34f
    const val inlineTitle = 17f
    const val dateLine = 14f
    const val heroAmount = 44f
    const val heroLine = 15f
    const val settledLine = 13f
    const val groupCount = 13f
    const val detail = 14f
    const val statValue = 16f
    const val settleIntro = 15f
    const val shareInput = 15f
    const val avatarInitial = 0.38f
    // D-221 (art.py sections 21 and 22): You's and a group's name, the Settled card's percent, the seal's number, the
    // progress pill's words.
    const val displayName = 24f
    const val settledPercent = 28f
    const val sealNumber = 15f
    const val pill = 13f
    // D-222 (art.py sections 26 and 27): the mode control's words, the live sample line, the slider's two A's (fixed).
    const val segment = 14f
    const val sample = 19f
    const val rangeSmall = 14f
    const val rangeLarge = 23f
    // D-223 (art.py sections 30, 32 and 34): a ledger card's name, a Lately line, the empty state's title.
    const val ledgerName = 16f
    const val feedLine = 15f
    const val emptyTitle = 20f
    // D-224 (art.py section 38): the done toast's words (the Look Book's 600 12 x 1.28).
    const val toast = 15f
    // D-225 (art.py sections 41 to 44): About's text and the Licences intro, a licence's own text, the lock screen's
    // title, About's "SKIPTI".
    const val prose = 15f
    const val footnote = 13f
    const val lockTitle = 28f
    const val wordmark = 32f

    /** Every constant by its name, in the order above. */
    val all: List<Pair<String, Float>> = listOf(
        "largeTitle" to largeTitle, "inlineTitle" to inlineTitle, "dateLine" to dateLine, "heroAmount" to heroAmount,
        "heroLine" to heroLine, "settledLine" to settledLine, "groupCount" to groupCount, "detail" to detail,
        "statValue" to statValue, "settleIntro" to settleIntro, "shareInput" to shareInput, "avatarInitial" to avatarInitial,
        "displayName" to displayName, "settledPercent" to settledPercent, "sealNumber" to sealNumber, "pill" to pill,
        "segment" to segment, "sample" to sample, "rangeSmall" to rangeSmall, "rangeLarge" to rangeLarge,
        "ledgerName" to ledgerName, "feedLine" to feedLine, "emptyTitle" to emptyTitle, "toast" to toast,
        "prose" to prose, "footnote" to footnote, "lockTitle" to lockTitle, "wordmark" to wordmark,
    )
}

/** D-217 final fix: how far a money amount shrinks to stay on one line before anything is cut: iOS's
 *  `.minimumScaleFactor(0.6)` on the hero amount and the stat tiles (OverviewScreen.swift). Outside [TypeScale],
 *  whose names are iOS's table one for one. */
const val AMOUNT_MIN_SCALE = 0.6f

/** The smallest size an amount drawn at [size] shrinks to. */
fun amountMinSize(size: Float): Float = AMOUNT_MIN_SCALE * size

object Type {
    /** Space Grotesk is ONE variable font; a single `Font()` entry would make
     *  Compose synthesise every weight from its default instance. D-174: each
     *  weight the app asks for is the font's own wght instance, as iOS drives
     *  the same axis (TypeRole). */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private val grotesk = FontFamily(
        listOf(400, 450, 500, 600, 700).map { w ->
            Font(
                R.font.spacegrotesk,
                FontWeight(w),
                variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                    androidx.compose.ui.text.font.FontVariation.weight(w),
                ),
            )
        },
    )
    /** D-215: IBM's own Plex Sans files (1.1.0, unmodified) replace the system face (the founder, 2026-10-15). */
    val sans = FontFamily(
        Font(R.font.ibmplexsans_regular, FontWeight(400)),
        Font(R.font.ibmplexsans_medium, FontWeight(500)),
        Font(R.font.ibmplexsans_semibold, FontWeight(600)),
        Font(R.font.ibmplexsans_bold, FontWeight(700)),
    )

    /** D-215: Vardr Runes Carved Regular, Vardr Labs' own font, the only face a rune is drawn in (the twin of
     *  iOS's `TypeRole.runes`). One weight: a rune is never bold (see [rune] and `RuneSpans.style`). */
    val runes = FontFamily(Font(R.font.vardrrunes_carvedregular, FontWeight.Normal))

    /** A rune drawn as art at a fixed [size]: Vardr Runes, Regular, never synthesised bold (the file has one weight,
     *  and iOS draws it as is). The caller adds the colour. */
    fun rune(size: TextUnit) = TextStyle(
        fontFamily = runes,
        fontWeight = FontWeight.Normal,
        fontSynthesis = androidx.compose.ui.text.font.FontSynthesis.None,
        fontSize = size,
    )

    /** Material's own type scale in the theme's BODY face (Plex Sans in Aurora and Norse, Space Grotesk in Vardr Labs),
     *  so a Text without a Type style draws what iOS's root `.font(TypeRole.body())` draws. Untracked, as iOS draws
     *  every TypeRole but Vardr Labs' micro label (D-215 fix wave, I1: Material's defaults track 0.1 to 0.5 sp);
     *  Material's sizes and line heights stay. The caller passes `Appearance.theme`, read during composition, and
     *  remembers the result keyed on it (AppRoot), so a theme change rebuilds it; never cache it across a switch. */
    fun materialTypography(theme: AppTheme): Typography {
        val family = familyOf(face(Role.BODY, theme))
        val d = Typography()
        fun s(t: TextStyle) = t.copy(fontFamily = family, letterSpacing = 0.sp)
        return Typography(
            displayLarge = s(d.displayLarge), displayMedium = s(d.displayMedium), displaySmall = s(d.displaySmall),
            headlineLarge = s(d.headlineLarge), headlineMedium = s(d.headlineMedium), headlineSmall = s(d.headlineSmall),
            titleLarge = s(d.titleLarge), titleMedium = s(d.titleMedium), titleSmall = s(d.titleSmall),
            bodyLarge = s(d.bodyLarge), bodyMedium = s(d.bodyMedium), bodySmall = s(d.bodySmall),
            labelLarge = s(d.labelLarge), labelMedium = s(d.labelMedium), labelSmall = s(d.labelSmall),
        )
    }

    /** The system face's buckets, kept: 650 and up Bold, 550 SemiBold, 450 Medium, else Regular. Bucketed here so
     *  Compose never has to pick a nearest of the four files (and iOS's TypeRole buckets the same way). */
    fun sansWeight(asked: Int): FontWeight = FontWeight(
        when {
            asked >= 650 -> 700
            asked >= 550 -> 600
            asked >= 450 -> 500
            else -> 400
        },
    )

    private val monoRegular = FontFamily(Font(R.font.ibmplexmono_regular))
    private val monoMedium = FontFamily(Font(R.font.ibmplexmono_medium))
    private val cormorant = FontFamily(
        Font(R.font.cormorantsc_semibold, FontWeight(600)),
        Font(R.font.cormorantsc_bold, FontWeight(700)),
    )
    private val plex = FontFamily(
        Font(R.font.ibmplexmono_regular, FontWeight(400)),
        Font(R.font.ibmplexmono_medium, FontWeight(500)),
    )

    /** The plan's table; the twin of iOS's TypeRole.face. */
    fun face(role: Role, theme: AppTheme): Face = when (role) {
        Role.DISPLAY -> if (theme == AppTheme.NORSE) Face.CORMORANT else Face.GROTESK
        Role.BODY -> if (theme == AppTheme.VARDR) Face.GROTESK else Face.SANS
        Role.NUMERIC -> if (theme == AppTheme.VARDR) Face.PLEX else Face.GROTESK
        Role.LABEL -> if (theme == AppTheme.VARDR) Face.PLEX else Face.SANS
        Role.MICRO -> when (theme) {
            AppTheme.AURORA -> Face.SANS
            AppTheme.NORSE -> Face.CORMORANT
            AppTheme.VARDR -> Face.PLEX
        }
    }

    /** `Appearance.theme` is snapshot state: reading it here recomposes every text on a change. */
    private fun family(role: Role): FontFamily = familyOf(face(role, Appearance.theme))

    private fun familyOf(face: Face): FontFamily = when (face) {
        Face.GROTESK -> grotesk
        Face.SANS -> sans
        Face.CORMORANT -> cormorant
        Face.PLEX -> plex
    }

    /** A weight the face has: Cormorant SC 600/700, Plex Mono 400/500, Plex Sans 400/500/600/700. */
    internal fun weightFor(role: Role, asked: Int): FontWeight = when (face(role, Appearance.theme)) {
        Face.CORMORANT -> FontWeight(if (asked >= 650) 700 else 600)
        Face.PLEX -> FontWeight(if (asked >= 500) 500 else 400)
        Face.SANS -> sansWeight(asked)
        else -> FontWeight(asked)
    }

    /** D-212: Vardr Labs' kickers are uppercase (in the phone's language, as
     *  iOS's textCase uses the environment's locale); the others sentence case. */
    fun kicker(text: String): String =
        if (Appearance.theme == AppTheme.VARDR) text.uppercase(java.util.Locale.getDefault()) else text

    /**
     * Display role: screen titles, balances, the settle-up number (§11.3).
     * Ported from TypeRole.display(): 40pt at weight 500 with tnum EXPLICIT
     * per ADR-0023 §6 (a balance is still a column of figures); the face
     * follows the theme (D-212).
     */
    fun display(dark: Boolean, weight: Int = 500) = TextStyle(
        fontFamily = family(Role.DISPLAY),
        fontWeight = weightFor(Role.DISPLAY, weight),
        fontSize = 40.sp,
        fontFeatureSettings = "tnum",
        color = Tok.text(dark),
    )

    /** D-213 (the founder, demo option A): a screen's title in the theme's display face, the Look Book's
     *  `.title` (700, Vardr Labs 600; tracking -0.01 em, Norse +0.03, Vardr Labs -0.02); the twin of iOS's
     *  navigation-bar titles. D-217: [size] is [TypeScale.largeTitle] or [TypeScale.inlineTitle] (SkiptiBar.kt),
     *  and only the bar calls it: no screen draws a title of its own, so no size is assumed. */
    fun title(dark: Boolean, size: Float) = TextStyle(
        fontFamily = family(Role.DISPLAY),
        fontWeight = weightFor(Role.DISPLAY, if (Appearance.theme == AppTheme.VARDR) 600 else 700),
        fontSize = size.sp,
        letterSpacing = titleTracking(Appearance.theme),
        color = Tok.text(dark),
    )

    fun titleTracking(theme: AppTheme): TextUnit = when (theme) {
        AppTheme.AURORA -> (-0.01).em
        AppTheme.NORSE -> 0.03.em
        AppTheme.VARDR -> (-0.02).em
    }

    /** Section label. D-174 (ADR-0038): 13 sp and a weight heavier. D-212:
     *  the face follows the theme; Vardr Labs tracks it at 0.22 em (its text
     *  goes through [kicker] for the uppercase). */
    fun microLabel(dark: Boolean) = TextStyle(
        fontFamily = family(Role.MICRO),
        fontWeight = weightFor(Role.MICRO, 600),
        fontSize = 13.sp,
        letterSpacing = microTracking(Appearance.theme),
        color = Tok.textDim(dark),
    )

    /** Tracking in em: Vardr Labs 0.22, the others none. */
    fun microTracking(theme: AppTheme): TextUnit =
        if (theme == AppTheme.VARDR) 0.22.em else TextUnit.Unspecified

    /** D-212 (fix wave 3, F5): the tab bar's label face is the BODY face (the Look Book draws tabs in `--p-body`,
     *  never uppercase or tracked); the twin of iOS's TypeRole.tabFace. */
    fun tabFace(theme: AppTheme): Face = face(Role.BODY, theme)

    /** A tab's label: the body face at weight 600, 11 sp as before, untracked. */
    fun tabLabel(dark: Boolean, size: Int = 11) = TextStyle(
        fontFamily = family(Role.BODY),
        fontWeight = weightFor(Role.BODY, 600),
        fontSize = size.sp,
        color = Tok.textDim(dark),
    )

    /** A button's words, and anything that reads as a heading at body size. */
    fun label(dark: Boolean, size: Int = 17) = TextStyle(
        fontFamily = family(Role.LABEL),
        fontWeight = weightFor(Role.LABEL, 600),
        fontSize = size.sp,
        fontFeatureSettings = "tnum",
        color = Tok.text(dark),
    )

    /** Numeric weight: Space Grotesk keeps D-174's 450/600; Plex 400/500. */
    private fun numericWeight(weight: FontWeight): FontWeight =
        if (face(Role.NUMERIC, Appearance.theme) == Face.PLEX) {
            weightFor(Role.NUMERIC, if (weight == FontWeight.Normal) 400 else 500)
        } else {
            if (weight == FontWeight.Normal) FontWeight(450) else FontWeight(600)
        }

    /**
     * Numeric role: amounts, counts, dates. D-174 (ADR-0038): Space Grotesk
     * with tabular figures (tnum), so columns still align. Twin of iOS's
     * TypeRole.numeric: Normal draws at 450, Medium and up at 600. D-212:
     * Vardr Labs draws IBM Plex Mono.
     */
    fun numeric(size: Int, weight: FontWeight = FontWeight.Medium, dark: Boolean) = numeric(size.toFloat(), weight, dark)

    /** D-217: the same role at a [TypeScale] size (a Float, in sp). */
    fun numeric(size: Float, weight: FontWeight = FontWeight.Medium, dark: Boolean) = TextStyle(
        fontFamily = family(Role.NUMERIC),
        fontWeight = numericWeight(weight),
        fontSize = size.sp,
        fontFeatureSettings = "tnum",
        color = Tok.text(dark),
    )

    fun numericColored(size: Int, color: Color, weight: FontWeight = FontWeight.Medium) =
        numericColored(size.toFloat(), color, weight)

    /** D-217: the same role at a [TypeScale] size (a Float, in sp). */
    fun numericColored(size: Float, color: Color, weight: FontWeight = FontWeight.Medium) =
        TextStyle(
            fontFamily = family(Role.NUMERIC),
            fontWeight = numericWeight(weight),
            fontSize = size.sp,
            fontFeatureSettings = "tnum",
            color = color,
        )

    /** Mono: IBM Plex Mono, kept only where letter shapes must be unambiguous
     *  to copy by hand: the Recovery Kit's words and the six-word phrase. */
    fun mono(size: Int, color: Color, weight: FontWeight = FontWeight.Medium) =
        TextStyle(
            fontFamily = if (weight == FontWeight.Normal) monoRegular else monoMedium,
            fontWeight = weight,
            fontSize = size.sp,
            fontFeatureSettings = "tnum",
            color = color,
        )

    /** D-217: a person's or group's name wherever it is drawn as a name: the theme's BODY face at 600, natural line
     *  height (no fixed one), scaled by the phone's font scale like any sp. The twin of iOS's TypeRole.name. */
    fun name(dark: Boolean, size: Int = 16) = TextStyle(
        fontFamily = family(Role.BODY),
        fontWeight = weightFor(Role.BODY, 600),
        fontSize = size.sp,
        color = Tok.text(dark),
    )

    /** D-217: body draws the face's natural line height, as iOS does (it fixed 22 sp, copied into every smaller line). */
    fun body(dark: Boolean) = TextStyle(
        fontFamily = family(Role.BODY),
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = Tok.text(dark),
    )

    /** D-217: the body face at another weight (You's handle is 500), snapped to a weight the face has. */
    fun bodyWeight(dark: Boolean, weight: Int) = body(dark).copy(fontWeight = weightFor(Role.BODY, weight))

    fun bodyDim(dark: Boolean) = body(dark).copy(color = Tok.textDim(dark))
}
