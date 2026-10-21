package com.vardrlabs.skipti

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * D-212 (ADR-0041): three themes by light and dark, the text size, and the
 * floors every pair must clear in every set; the twin of iOS AppearanceTests.
 */
class AppearanceTest {

    @After
    fun reset() = Appearance.setForTest()

    /** D-222 (L63): the twin of iOS's testTheStepSliderGoldens (.drafts/d222/art.py section 29; width 300, thumb 20):
     *  half up (floor(v + 0.5)) on both shells, clamped; never `roundToInt()`, which differs from Swift at -1.5. */
    @Test
    fun the_step_slider_goldens() {
        val at = listOf(-20f to -2, 0f to -2, 10f to -2, 37.9f to -2, 38f to -1, 66f to -1, 150f to 1, 290f to 3, 300f to 3, 400f to 3)
        for ((x, step) in at) assertEquals("x $x", step, StepSlider.step(x, 300f, 20f))
        val centres = listOf(-2 to 10f, -1 to 66f, 0 to 122f, 1 to 178f, 2 to 234f, 3 to 290f)
        for ((step, x) in centres) assertEquals("step $step", x, StepSlider.centre(step, 300f, 20f), 1e-4f)
        for ((step, x) in centres) assertEquals("a thumb's centre picks its step", step, StepSlider.step(x, 300f, 20f))
        val adjusted = listOf(Triple(-2, -1, -2), Triple(-2, 1, -1), Triple(0, 1, 1), Triple(3, 1, 3), Triple(3, -1, 2))
        for ((step, by, result) in adjusted) assertEquals("$step by $by", result, StepSlider.adjusted(step, by))
    }

    /** D-222 fix wave (look F4, X3's twin): the track runs between the thumb's two end centres, so at either end its
     *  end and the fill's start lie under the thumb; the fill runs from the first to the thumb's centre (width 300,
     *  thumb 20: 10 to 290, the fill 0 wide at -2 and 112 at 0). */
    @Test
    fun the_slider_track_lies_under_the_thumb_at_both_ends() {
        assertEquals(10f..290f, StepSlider.track(300f, 20f))
        assertEquals("no fill at the smallest step", 0f, StepSlider.centre(TextSize.MIN, 300f, 20f) - StepSlider.track(300f, 20f).start, 1e-4f)
        assertEquals("the track ends at the largest step's centre", StepSlider.centre(TextSize.MAX, 300f, 20f), StepSlider.track(300f, 20f).endInclusive, 1e-4f)
        assertEquals(112f, StepSlider.centre(0, 300f, 20f) - StepSlider.track(300f, 20f).start, 1e-4f)
    }

    /** D-222 fix wave (I-3): a hardware keyboard or D-pad moves the slider one step, as Material's Slider did: right and
     *  up larger, left and down smaller, every other key passes on. At an end the key passes on too (focus can leave). */
    @Test
    fun the_slider_keys() {
        val k = androidx.compose.ui.input.key.Key
        assertEquals(1, StepSlider.byKey(k.DirectionRight))
        assertEquals(1, StepSlider.byKey(k.DirectionUp))
        assertEquals(-1, StepSlider.byKey(k.DirectionLeft))
        assertEquals(-1, StepSlider.byKey(k.DirectionDown))
        for (other in listOf(k.Tab, k.Enter, k.Spacebar, k.A, k.PageUp)) assertEquals("$other", null, StepSlider.byKey(other))
        assertEquals("a step that moves", 1, StepSlider.keyed(0, k.DirectionRight))
        assertEquals("a step that moves", -2, StepSlider.keyed(-1, k.DirectionDown))
        assertEquals("at the top, the key passes on", null, StepSlider.keyed(3, k.DirectionUp))
        assertEquals("at the bottom, the key passes on", null, StepSlider.keyed(-2, k.DirectionLeft))
        assertEquals("another key passes on", null, StepSlider.keyed(0, k.Tab))
    }

    /** D-222 (L62): the twin of iOS's testTheModeChoiceShown. */
    @Test
    fun the_mode_choice_shown() {
        for (stored in AppearanceMode.entries) {
            assertEquals("$stored", ModeChoice(AppearanceMode.DARK, false), ModeChoice.shown(AppTheme.NORSE, stored))
            for (theme in listOf(AppTheme.AURORA, AppTheme.VARDR)) {
                assertEquals("$theme $stored", ModeChoice(stored, true), ModeChoice.shown(theme, stored))
            }
        }
    }

    /** D-234 (A1, the founder's): the twin of iOS's testTheSampleLine: "Lunch at Shake Shack" and 48 US dollars for
     *  everyone, the amount from the real core ($48.00 in both locales core knows). */
    @Test
    fun the_sample_line() {
        assertEquals(listOf("Lunch at Shake Shack", "\$48.00"), SampleLine.parts("\$48.00"))
        assertEquals("\$48.00", SampleLine.amount(uniffi.skipti_core.LocaleTag.EN_US))
        assertEquals("the same line for everyone", "\$48.00", SampleLine.amount(uniffi.skipti_core.LocaleTag.EN_IN))
        assertEquals("USD", SampleLine.CURRENCY)
        assertTrue("the screen reads the fixed line", "val amount = remember { SampleLine.amount() }" in hub())
        val source = java.io.File("src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt").readText()
        val body = source.substringAfter("object SampleLine {").substringBefore("\n}\n")
        assertFalse("never the person's default currency", "DefaultCurrency" in body)
        assertTrue("minor units from core", "ffiMoneyParseMinor(WHOLE_AMOUNT, CURRENCY)" in body)
        assertTrue("the string from core", "ffiMoneyFormat(" in body)
        for (banned in listOf("NumberFormat", "String.format", ".format(", "\"%")) assertFalse(banned, banned in body)
        // The twin: iOS's words, amount and currency are these.
        val swift = java.io.File("../../ios/Skipti/App/AccountHubScreen.swift").readText()
            .substringAfter("enum SampleLine {").substringBefore("\n}\n")
        assertTrue("static let title = \"${SampleLine.TITLE}\"" in swift)
        assertTrue("static let wholeAmount = \"${SampleLine.WHOLE_AMOUNT}\"" in swift)
        assertTrue("static let currency = \"${SampleLine.CURRENCY}\"" in swift)
    }

    private fun hub() = java.io.File("src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt").readText()

    /** A private composable's body in AccountHubScreen.kt, up to its closing brace at the start of a line. */
    private fun fn(name: String): String {
        val src = hub()
        assertTrue("$name exists", "private fun $name(" in src)
        return src.substringAfter("private fun $name(").substringBefore("\n}\n")
    }

    /** One item of the Appearance page's `when`, from its arm to the next. */
    private fun item(from: String, to: String) = hub().substringAfter("SettingItem.$from ->").substringBefore("SettingItem.$to ->")

    /** D-222 (L59, Design 4): Theme and Light or dark sit on the ground (no card); Text size is ONE card holding the
     *  slider row, the sample line, then the caption, its label above the card. Material's chips and slider are gone. The
     *  twin of iOS's testAppearanceSitsOnTheGround. */
    @Test
    fun appearance_sits_on_the_ground() {
        val theme = item("THEME", "APPEARANCE_MODE")
        val mode = item("APPEARANCE_MODE", "TEXT_SIZE")
        // D-225 T5: Security's items are drawn by SecurityPage; the section loop names them on one line.
        val size = hub().substringAfter("SettingItem.TEXT_SIZE ->").substringBefore("SettingItem.APP_LOCK, SettingItem.PRIVACY")
        for ((name, body) in listOf("theme" to theme, "mode" to mode)) {
            assertFalse("$name sits on the ground", "Card(dark)" in body)
            assertFalse("$name: no glass", "glassCard(" in body)
        }
        assertTrue("the theme cards", "ThemeCard(" in theme)
        assertTrue("the drawn mode control", "ModeControl(dark)" in mode)
        assertEquals("one card for text size", 1, size.split("Card(dark)").size - 1)
        val label = size.indexOf("SectionLabel(AppearanceCopy.CARDS[2], dark)")
        val card = size.indexOf("Card(dark)")
        val slider = size.indexOf("TextSizeSlider(dark)")
        val sample = size.indexOf("SampleLine.parts(")
        val caption = size.indexOf("AppearanceCopy.TEXT_SIZE_CAPTION")
        assertTrue("label, card, slider, sample, caption: $label $card $slider $sample $caption", label in 0 until card && card < slider && slider < sample && sample < caption)
        assertTrue("the sample in the sample size", "Type.name(dark, TypeScale.sample.toInt())" in size)
        // The Norse line alone remains under the control, in Norse only; the mode's own lines are gone.
        assertTrue("Norse only", "if (Appearance.theme.forcesDark) {" in mode && "AppearanceCopy.NORSE_ALWAYS_DARK" in mode)
        assertFalse("no line in the other themes", "else" in mode)
        val src = hub()
        for (gone in listOf("FilterChip(", "SliderDefaults", "chipColors(", ".detail,", "Always light.", "Always dark.", "Follows your phone")) {
            assertFalse("AccountHubScreen.kt still has $gone", gone in src)
        }
        assertFalse("no Material Slider", Regex("""(?<![A-Za-z])Slider\(""").containsMatchIn(src))
        val appearance = java.io.File("src/main/java/com/vardrlabs/skipti/Appearance.kt").readText()
        assertFalse("AppearanceMode has no detail", "val detail" in appearance || "Always light." in appearance)
    }

    /** D-222 (L62, Design 6): the drawn mode control is a radio group to TalkBack (each segment a RadioButton,
     *  "n of 3"), every segment the full 48 dp row; the track and chosen segment the theme's pill shape; Norse dims the
     *  whole control and disables it. The twin of iOS's testTheModeControlIsDrawnAndAccessible. */
    @Test
    fun the_mode_control_is_a_radio_group() {
        val b = fn("ModeControl")
        for (s in listOf(
            "val choice = ModeChoice.shown(Appearance.theme, Appearance.mode)", ".selectableGroup()", ".selectable(",
            "role = Role.RadioButton", "enabled = choice.enabled", "selected = mode == choice.chosen",
            "heightIn(min = ThemeArt.m(\"touchTarget\").dp)", "ThemeArt.pillShape(Appearance.theme)", "ThemeArt.m(\"modeTrack\")",
            "ThemeArt.m(\"modeInset\")", "Type.name(dark, TypeScale.segment.toInt())", "ButtonLook.DISABLED_ALPHA",
            "shadow(ThemeArt.m(\"segmentShadowRadius\").dp", "Tok.accentSoft(dark)", "Tok.surface(dark)", "Tok.textDim(dark)",
            "maxLines = 1", "TextAutoSize.StepBased(", "Appearance.setMode(context, mode)",
            // Fix wave (I-2): the row grows with the words like iOS's: the track sits `margin` inside the row, the chosen
            // pill `margin + inset` inside its segment, so the track stays `inset` outside the pill at every text size.
            "val margin = (ThemeArt.m(\"touchTarget\") - track) / 2",
            "Box(Modifier.matchParentSize().padding(vertical = margin.dp).background(Tok.accentSoft(dark), shape))",
            ".padding(vertical = (margin + inset).dp)", "heightIn(min = (track - 2 * inset).dp)",
        )) assertTrue("ModeControl: $s", s in b)
        assertFalse("no fixed track height", "height(track.dp)" in b)
        // Fix wave (look item 6): each segment is ONE node, the words themselves, so Compose gives that node the
        // RadioButton class with its text and state (a segment with a child Text gets an empty RadioButton twin instead).
        val segment = b.substringAfter("AppearanceMode.entries.forEach")
        assertFalse("the segment is its words, no Box around them", "Box(" in segment)
        assertTrue("the words take the selection", Regex("""Text\(\s*mode\.label,""").containsMatchIn(segment) && ".selectable(" in segment.substringAfter("modifier = Modifier"))
        assertEquals("every segment is the full touch row, and the row itself", 2, b.split("heightIn(min = ThemeArt.m(\"touchTarget\").dp)").size - 1)
        assertFalse("no Material segmented button", "SegmentedButton" in b)
    }

    /** D-222 (L63, L65, Design 7): the drawn slider is a SeekBar TalkBack can adjust (range info, set progress, the
     *  spoken value) and a pointer handler that maps x to a step; the track and fill the theme's meter shape, the thumb
     *  its person tile; the A's fixed, the large one bold, both in `text`. The twin of iOS's testTheSliderIsDrawnAndAdjustable. */
    @Test
    fun the_slider_is_an_adjustable_seekbar() {
        val b = fn("TextSizeSlider")
        for (s in listOf(
            "progressBarRangeInfo = ProgressBarRangeInfo(", "setProgress {", "stateDescription = TextSize.accessibilityValue(",
            "contentDescription = AppearanceCopy.CARDS[2]", ".focusable()", "heightIn(min = ThemeArt.m(\"touchTarget\").dp)",
            "ThemeArt.meterShape(Appearance.theme)", "TileShape.of(Appearance.theme, person = true)", "Tok.ringTrack(dark)",
            "ThemeArt.m(\"sliderThumb\")", "ThemeArt.m(\"sliderRing\")", "ThemeArt.m(\"sliderTrack\")", "StepSlider.centre(",
            "detectTapGestures", "detectHorizontalDragGestures", "steps = TextSize.MAX - TextSize.MIN - 1",
            // A drag lives through every step it writes: the slider's density keeps a fixed font scale (D-222 T3 look).
            "val density = Density(LocalDensity.current.density)", "CompositionLocalProvider(LocalDensity provides density)",
        )) assertTrue("TextSizeSlider: $s", s in b)
        // Fix wave (look F4): the track from the first end centre to the last, the fill from the first to the thumb; the
        // ring a stroke centred on the thumb's shape inset by half the ring, iOS's geometry (not an inside border).
        for (s in listOf(
            "val track = StepSlider.track(maxWidth.value, thumb)",
            "Modifier.offset(x = track.start.dp).width((track.endInclusive - track.start).dp).height(bar.dp).background(Tok.ringTrack(dark), meter)",
            "Modifier.offset(x = track.start.dp).width((centre - track.start).dp).height(bar.dp).background(Tok.accent(dark), meter)",
            "thumbShape.createOutline(Size(size.width - r, size.height - r), layoutDirection, this)",
            "translate(r / 2, r / 2) { drawOutline(outline, accent, style = Stroke(width = r)) }",
        )) assertTrue("TextSizeSlider: $s", s in b)
        assertFalse("no inside border on the thumb", ".border(" in b)
        val pointer = b.substringAfter(".pointerInput(", "")
        assertTrue("the pointer handler picks a step", "StepSlider.step(" in pointer)
        // Fix wave (look F2): the handler starts at the first event; a dispatched launch is not yet waiting when that
        // event is delivered, so the first gesture was lost. Both detectors start undispatched.
        assertEquals("both detectors start undispatched", 2, pointer.split("launch(start = CoroutineStart.UNDISPATCHED) {").size - 1)
        assertFalse("no dispatched launch", "launch {" in pointer)
        // Fix wave (I-3): a key moves it, through the pure decision; the key handler sits before the focus target.
        val keys = b.substringAfter(".onKeyEvent {", "").substringBefore(".focusable()")
        assertTrue("onKeyEvent before focusable()", ".onKeyEvent {" in b && b.indexOf(".onKeyEvent {") < b.indexOf(".focusable()"))
        for (s in listOf("it.type == KeyEventType.KeyDown", "StepSlider.keyed(Appearance.textStep, it.key)", "Appearance.setTextStep(context,")) {
            assertTrue("the key handler: $s", s in keys)
        }
        assertTrue("only a new step is written", "if (s != Appearance.textStep) Appearance.setTextStep(context, s)" in b)
        val progress = b.substringAfter("setProgress {").substringBefore("true\n")
        assertTrue("half up, clamped", "StepSlider.clamped(kotlin.math.floor(v + 0.5f).toInt())" in progress)
        val small = b.lines().single { "TypeScale.rangeSmall" in it }
        val large = b.lines().single { "TypeScale.rangeLarge" in it }
        assertTrue("the large A is bold", "FontWeight.Bold" in large)
        assertFalse("the small A is regular", "FontWeight.Bold" in small)
        for (a in listOf(small, large)) {
            assertTrue("fixed: dp to sp", ".dp.toSp()" in a)
            assertTrue("an A in text: $a", "Type.body(dark)" in a)
        }
        assertFalse("the A's are text, not dim", "Tok.textDim(dark)" in b)
        assertEquals("the A's are hidden from TalkBack", 2, b.split("Modifier.clearAndSetSemantics {}").size - 1)
    }

    /** D-222 (L60, L61, Q9 a, Design 5): each swatch draws its OWN theme's dark art, still, whatever the mode or the
     *  phone: Aurora the hero's night (sky, stars, curtains at the hero strengths), Norse ᚠᚷᛗᛏ in its gold on its
     *  ground, Vardr Labs' dots and frame marks on its dark ground; colours by token in a fixed set. The card is the
     *  current theme's control shape and the swatch its panel shape. The twin of iOS's testSwatchesAreEachThemesDarkArt. */
    @Test
    fun swatches_are_each_themes_dark_art() {
        val sw = fn("ThemeSwatch")
        for (s in listOf(
            "AppTheme.AURORA ->", "AppTheme.NORSE ->", "AppTheme.VARDR ->", "nightSkyBody()",
            "curtains(Curtains.STILL, true, Strength.of(\"auroraHeroCurtains\"), Strength.of(\"auroraHeroStars\"), layer, cache)",
            "Runes.BALANCE + Runes.EXPENSES + Runes.GROUPS + Runes.SETTLE", "Type.rune(runeSize(\"swatchRune\", density))",
            "ThemeArt.m(\"swatchRuneTracking\")", "ArtGeometry.gridDots(", "ThemeArt.m(\"swatchGridPitch\")",
            "ThemeArt.m(\"swatchGridDot\")", "ArtGeometry.frameMarks(", "ThemeArt.m(\"swatchMarkInset\")", "ThemeArt.m(\"swatchMarkLength\")",
            "Tok.bg.of(ThemeSet.NORSE)", "Tok.accent.of(ThemeSet.NORSE)", "Tok.bg.of(ThemeSet.VARDR_DARK)", "Tok.ring.of(ThemeSet.VARDR_DARK)",
            "Tok.accent.of(ThemeSet.VARDR_DARK)", "ThemeArt.panelShape(Appearance.theme, ThemeArt.m(\"swatchRadius\").toInt())",
            "ThemeArt.m(\"swatchHeight\")", "clearAndSetSemantics {}",
        )) assertTrue("ThemeSwatch: $s", s in sw)
        for (s in listOf("VARDR_LIGHT", "AURORA_LIGHT", "ThemeSet.resolve(", "isDark(")) assertFalse("ThemeSwatch reads $s", s in sw)
        val card = fn("ThemeCard")
        for (s in listOf(
            "ThemeArt.controlShape(Appearance.theme)", "ThemeArt.m(\"themePad\")", "ThemeArt.m(\"themeGap\")", "ThemeSwatch(theme)",
            "Type.name(dark, 14)", "role = Role.RadioButton",
        )) assertTrue("ThemeCard: $s", s in card)
        val src = hub()
        for (s in listOf("previewDark", "UI_MODE_NIGHT", "artCanvas(", "phoneDark")) assertFalse("AccountHubScreen.kt reads $s", s in src)
        val motion = java.io.File("src/main/java/com/vardrlabs/skipti/ThemeMotion.kt").readText()
        assertTrue("the night sky is shared", "internal fun DrawScope.nightSkyBody()" in motion)
    }

    /** D-222 (L57): the glass card takes a padding (You's list pads 0 and lays out its own rows); every theme's branch
     *  pads it; the default stays 16 (each card's own value is CardPaddingParityTest's). */
    @Test
    fun glass_card_takes_a_padding() {
        val aurora = java.io.File("src/main/java/com/vardrlabs/skipti/Aurora.kt").readText()
        val body = aurora.substringAfter("fun Modifier.glassCard(").substringBefore("\n}\n")
        assertTrue("padding: Int = 16" in body)
        assertEquals("each branch pads the asked padding", 3, body.split(".padding(padding.dp)").size - 1)
        assertFalse("no fixed 16", ".padding(16.dp)" in body)
        // D-234 wave (decision 102): which cards ask for their own padding, and that each asks iOS's value, is
        // CardPaddingParityTest's (it replaced the allowlist of files that was here).
    }

    private fun hex(c: Color): String {
        fun ch(v: Float) = "%02X".format(Math.round(v * 255))
        return "#" + ch(c.red) + ch(c.green) + ch(c.blue)
    }

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (la, lb) = luminance(a) to luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun over(fg: Color, alpha: Float, ground: Color) = Color(
        ground.red + (fg.red - ground.red) * alpha,
        ground.green + (fg.green - ground.green) * alpha,
        ground.blue + (fg.blue - ground.blue) * alpha,
    )

    /** D-221 (art.py sections 18 to 23): E4's pairs hold their floors in every set, at art.py's own values (two
     *  decimals): the ring's arc and the meter's dim end on the track (3:1), the soft avatar's initials and the seal's
     *  number (accent on accentSoft, 4.5), the badge's initials (onAccent on accent, 4.5), Vardr Labs' progress dot on
     *  the page (3:1), and the celebration's words in `text` over the page dimmed to [CelebrationTiming.DIM_TO] (4.5:
     *  the worst ground is the strongest content colour, text, at that alpha over bg). */
    @Test
    fun e4_pairs_hold_their_floors() {
        val sets = ThemeSet.entries
        fun pair(fg: Tok, bg: Tok) = sets.map { contrast(fg.of(it), bg.of(it)) }
        fun check(name: String, got: List<Double>, want: List<Double>, floor: Double) {
            for ((i, r) in got.withIndex()) assertTrue("$name ${sets[i]} $r >= $floor", r >= floor)
            assertEquals(name, want, got.map { Math.round(it * 100) / 100.0 })
        }
        check("ring on ringTrack", pair(Tok.ring, Tok.ringTrack), listOf(3.26, 8.98, 7.08, 3.59, 8.46), 3.0)
        check("meterStart on ringTrack", pair(Tok.meterStart, Tok.ringTrack), listOf(3.26, 5.85, 5.53, 3.99, 6.78), 3.0)
        check("accent on accentSoft", pair(Tok.accent, Tok.accentSoft), listOf(4.53, 8.93, 7.81, 5.07, 7.86), 4.5)
        check("onAccent on accent", pair(Tok.onAccent, Tok.accent), listOf(5.30, 11.87, 9.60, 5.68, 8.60), 4.5)
        val dot = listOf(ThemeSet.VARDR_LIGHT, ThemeSet.VARDR_DARK).map { contrast(Tok.ring.of(it), Tok.bg.of(it)) }
        for (r in dot) assertTrue("Vardr Labs dot $r", r >= 3.0)
        assertEquals("Vardr Labs dot", listOf(4.88, 10.75), dot.map { Math.round(it * 100) / 100.0 })
        // Blended in doubles, as art.py does (a Compose `Color` built from floats is stored at 8 bits a channel, which moves
        // the second decimal: Aurora dark 9.67 against 9.72).
        fun rgb(c: Color) = doubleArrayOf(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble())
        fun lum(c: DoubleArray): Double {
            fun ch(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
        }
        val dimmed = sets.map { set ->
            val text = rgb(Tok.text.of(set))
            val bg = rgb(Tok.bg.of(set))
            val worst = DoubleArray(3) { bg[it] + (text[it] - bg[it]) * CelebrationTiming.DIM_TO }
            (max(lum(text), lum(worst)) + 0.05) / (min(lum(text), lum(worst)) + 0.05)
        }
        check("message on the dimmed page", dimmed, listOf(10.03, 9.72, 9.50, 8.58, 8.95), 4.5)
    }

    /** Every token resolves its own set's value through `Tok(dark)`. */
    @Test
    fun every_token_resolves_its_sets_value() {
        for (set in ThemeSet.entries) {
            Appearance.setForTest(theme = set.theme)
            for (tok in Tok.entries) assertEquals("$set $tok", hex(tok.of(set)), hex(tok(set.isDark)))
        }
        assertEquals("#EEF5F3", hex(Tok.bg.of(ThemeSet.AURORA_LIGHT)))
        assertEquals("#07080E", hex(Tok.bg.of(ThemeSet.NORSE)))
        assertEquals("#9DB0BF", hex(Tok.accent.of(ThemeSet.VARDR_DARK)))
        assertEquals("Norse has no light set", ThemeSet.NORSE, ThemeSet.resolve(AppTheme.NORSE, dark = false))
    }

    /** D-242 (U24, the founder's "Vibrant", 2026-10-20): the palette and the group tones are the scripts' table, copied
     *  from .drafts/d220/palette.py --kotlin (groundFaint and groundAccent by D-214/D-220/D-226's rules: Norse takes
     *  textDim and the accent, art.py section 48), never read back. D-242 moved the Norse column only: Aurora's and Vardr
     *  Labs' columns are the ones they had before it, so a change to any of them fails here; the twin of iOS's
     *  testThePaletteIsTheScriptsTableAndOnlyNorseMoved. */
    @Test
    fun the_palette_is_the_scripts_table_and_only_norse_moved() {
        val want = mapOf(
            Tok.bg to listOf("#EEF5F3", "#050A12", "#07080E", "#E8ECEF", "#0B0F13"),
            Tok.surface to listOf("#FFFFFF", "#101A24", "#111218", "#EEF1F4", "#0E141A"),
            Tok.surfaceRaised to listOf("#F6FAF9", "#1D2730", "#1F1F23", "#EBEEF2", "#1B2127"),
            Tok.hairline to listOf("#D5E3DF", "#1D2C38", "#4A3B1B", "#C3CDD4", "#1D2731"),
            Tok.text to listOf("#0E1B1A", "#E6F5F0", "#F3EAD3", "#182430", "#E9EEF3"),
            Tok.textDim to listOf("#4D615E", "#8FA9A4", "#B5A888", "#46555F", "#8FA0AE"),
            Tok.textFaint to listOf("#607370", "#7A918E", "#9A8F76", "#5E6C75", "#7A8995"),
            Tok.owed to listOf("#842D2A", "#EC7D79", "#EE8A58", "#9A4D16", "#E89055"),
            Tok.owing to listOf("#356F2F", "#97C87E", "#7FD4C1", "#0A6C92", "#5AD0F2"),
            Tok.neutral to listOf("#607370", "#7A918E", "#9A8F76", "#5E6C75", "#7A8995"),
            Tok.watch to listOf("#7A611B", "#E8C170", "#E8C170", "#7A611B", "#E8C170"),
            Tok.accent to listOf("#0B7A5F", "#45E6B0", "#DFB043", "#4C6172", "#9DB0BF"),
            Tok.groundFaint to listOf("#4D615E", "#8FA9A4", "#B5A888", "#5E6C75", "#7A8995"),
            Tok.groundAccent to listOf("#4D615E", "#45E6B0", "#DFB043", "#475B6B", "#9DB0BF"),
            Tok.onAccent to listOf("#FFFFFF", "#04140F", "#130D02", "#EEF1F4", "#0B0F13"),
            Tok.accentSoft to listOf("#DDF2EA", "#10302A", "#2A2211", "#DFE5E9", "#121A21"),
            Tok.owingSoft to listOf("#EBF1EA", "#283934", "#253536", "#D7E4EA", "#1C3641"),
            Tok.owedSoft to listOf("#F3EAEA", "#382C33", "#392824", "#E6E1DE", "#352A25"),
            Tok.ring to listOf("#0C8A6C", "#45E6B0", "#DFB043", "#0A6D93", "#5AD0F2"),
            Tok.ringTrack to listOf("#D5E3DF", "#1D2C38", "#2B2A2E", "#C3CDD4", "#1D2731"),
            Tok.meterStart to listOf("#0C8A6C", "#1FB6C9", "#9AA2AE", "#4C6172", "#9DB0BF"),
            Tok.meterEnd to listOf("#2BC9A0", "#45E6B0", "#DFB043", "#0A6D93", "#5AD0F2"),
            Tok.heroStart to listOf("#03070D", "#03070D", "#211A0A", "#EEF1F4", "#0B0F13"),
            Tok.heroMid to listOf("#071321", "#071321", "#16120C", "#EEF1F4", "#0B0F13"),
            Tok.heroEnd to listOf("#0A1B26", "#0A1B26", "#0A0A0F", "#EEF1F4", "#0B0F13"),
            Tok.onHero to listOf("#EAFBF4", "#EAFBF4", "#F3EAD3", "#182430", "#E9EEF3"),
        )
        assertEquals(Tok.entries.toSet(), want.keys)
        for ((tok, column) in want) assertEquals(tok.name, column, ThemeSet.entries.map { hex(tok.of(it)) })
        val tones = mapOf(
            GroupTone.CORAL to (listOf("#FFE4DA", "#3A2119", "#261F21", "#DDDADA", "#2C2829") to listOf("#B53F18", "#FF9C78", "#E1AA96", "#875342", "#DAAD9D")),
            GroupTone.BLUE to (listOf("#E3EBFC", "#17223D", "#1E212C", "#D7DCE4", "#262B34") to listOf("#2F5BBF", "#93B4FF", "#ABBDE7", "#4F6089", "#B1C0E1")),
            GroupTone.TEAL to (listOf("#DDF5F0", "#12302B", "#172125", "#D1DBDD", "#1E2A2C") to listOf("#0B7A6B", "#5FD6C4", "#7ABBB1", "#2A5B55", "#80B5AD")),
            GroupTone.AMBER to (listOf("#FFF1D6", "#33270F", "#23201E", "#D8D9D7", "#2A2926") to listOf("#8A5A00", "#F2C063", "#D2B683", "#644E26", "#CBB48A")),
            GroupTone.SKY to (listOf("#DFF1FB", "#10293A", "#1B222A", "#D2DBE0", "#222B31") to listOf("#0B6A96", "#7CC9F0", "#96C0D6", "#315C70", "#9CBFD0")),
            GroupTone.PINK to (listOf("#FFE3F0", "#3A1A2A", "#261E27", "#DCD9DF", "#2D282F") to listOf("#A8286A", "#FF8CC2", "#E5A6C4", "#854B69", "#DFACC4")),
        )
        assertEquals(GroupTone.entries.toSet(), tones.keys)
        for ((tone, w) in tones) {
            assertEquals("$tone fill", w.first, ThemeSet.entries.map { hex(tone.fillOf(it)) })
            assertEquals("$tone letter", w.second, ThemeSet.entries.map { hex(tone.letterOf(it)) })
        }
    }

    /** ADR-0036's and ADR-0038's floors, extended by ADR-0041 to every set. */
    @Test
    fun every_pair_clears_its_contrast_floor_in_every_theme_and_mode() {
        for (set in ThemeSet.entries) {
            fun c(t: Tok) = t.of(set)
            val grounds = mutableListOf("bg" to c(Tok.bg), "surface" to c(Tok.surface), "surfaceRaised" to c(Tok.surfaceRaised))
            if (set.theme.hasGlass) {
                grounds += "glass78" to over(c(Tok.surface), 0.78f, c(Tok.bg))
                grounds += "glass82" to over(c(Tok.surface), 0.82f, c(Tok.bg))
            }
            val floors = listOf(
                Tok.text to 7.0, Tok.textDim to 4.5, Tok.textFaint to 4.5, Tok.owed to 4.5, Tok.owing to 4.5,
                Tok.neutral to 4.5, Tok.watch to 4.5, Tok.accent to 4.5, Tok.groundFaint to 4.5, Tok.groundAccent to 4.5,
            )
            for ((fg, floor) in floors) for ((name, ground) in grounds) {
                val ratio = contrast(c(fg), ground)
                assertTrue("$set: $fg on $name is $ratio", ratio >= floor)
            }
            for ((fg, fill) in listOf(
                Tok.onAccent to Tok.accent, Tok.owing to Tok.owingSoft, Tok.owed to Tok.owedSoft, Tok.accent to Tok.accentSoft,
            )) {
                val ratio = contrast(c(fg), c(fill))
                assertTrue("$set: $fg on $fill is $ratio", ratio >= 4.5)
            }
            assertTrue("$set: ring", contrast(c(Tok.ring), c(Tok.bg)) >= 3.0)
            for (stop in listOf(Tok.heroStart, Tok.heroMid, Tok.heroEnd)) {
                val ground = c(stop)
                assertTrue("$set: onHero on $stop", contrast(c(Tok.onHero), ground) >= 7.0)
                assertTrue("$set: dim hero text on $stop", contrast(over(c(Tok.onHero), 0.82f, ground), ground) >= 4.5)
                val film = over(c(Tok.onHero), Aurora.HERO_CHIP_ALPHA, ground)
                assertTrue("$set: hero chip on $stop", contrast(c(Tok.onHero), film) >= 4.5)
            }
            assertTrue("$set: the hero's Settle up label", contrast(c(Tok.heroStart), c(Tok.onHero)) >= 4.5)
        }
    }

    /** D-179's tiles, per set. */
    @Test
    fun every_group_tone_letter_is_readable_on_its_fill_in_every_set() {
        for (set in ThemeSet.entries) for (tone in GroupTone.entries) {
            assertTrue("$set $tone", contrast(tone.letterOf(set), tone.fillOf(set)) >= 4.5)
        }
        // Goldens, copied from the approved palette (.drafts/d212/palette.py), not read back.
        assertEquals(Color(0xFF261F21), GroupTone.CORAL.fillOf(ThemeSet.NORSE))
        assertEquals(Color(0xFF875342), GroupTone.CORAL.letterOf(ThemeSet.VARDR_LIGHT))
    }

    /** D-220 (L19): Vardr Labs' outlined pill keeps each tone's words at 4.5 on every ground it can sit on, goldens from
     *  art.py section 13; the twin of iOS's testOutlinedPillWordsClearTheirGrounds. */
    @Test
    fun outlined_pill_words_clear_their_grounds() {
        val golden = mapOf(
            ThemeSet.VARDR_LIGHT to mapOf(
                PillTone.ACCENT to listOf(5.42, 5.68, 5.53), PillTone.OWNER to listOf(6.65, 6.96, 6.78),
                PillTone.SELECTED to listOf(5.42, 5.68, 5.53), PillTone.UNSELECTED to listOf(13.25, 13.89, 13.53),
            ),
            ThemeSet.VARDR_DARK to mapOf(
                PillTone.ACCENT to listOf(8.60, 8.29, 7.26), PillTone.OWNER to listOf(9.56, 9.20, 8.07),
                PillTone.SELECTED to listOf(8.60, 8.29, 7.26), PillTone.UNSELECTED to listOf(16.47, 15.87, 13.91),
            ),
        )
        val hero = mapOf(ThemeSet.VARDR_LIGHT to 13.89, ThemeSet.VARDR_DARK to 16.47)
        for (set in listOf(ThemeSet.VARDR_LIGHT, ThemeSet.VARDR_DARK)) {
            for (onPage in listOf(false, true)) {
                for ((tone, want) in golden.getValue(set)) {
                    val paint = PillPaint.of(tone, PillLook.OUTLINED, onPage)
                    val words = paint.words.of(set)
                    listOf(Tok.bg, Tok.surface, Tok.surfaceRaised).forEachIndexed { i, ground ->
                        val r = contrast(words, ground.of(set))
                        assertTrue("$set $tone words on ${ground.name}", r >= 4.5)
                        assertEquals("$set $tone on ${ground.name}: art.py's value", want[i], r, 0.01)
                    }
                    if (tone == PillTone.SELECTED) for (ground in listOf(Tok.bg, Tok.surface, Tok.surfaceRaised)) {
                        assertTrue("$set selected border on ${ground.name}", contrast(paint.border!!.ink.of(set), ground.of(set)) >= 3.0)
                    }
                }
            }
            val words = PillPaint.of(PillTone.HERO, PillLook.OUTLINED).words.of(set)
            for (stop in listOf(Tok.heroStart, Tok.heroMid, Tok.heroEnd)) {
                val r = contrast(words, stop.of(set))
                assertTrue("$set hero pill words on ${stop.name}", r >= 4.5)
                assertEquals("$set hero pill words on ${stop.name}: art.py's value", hero.getValue(set), r, 0.01)
            }
        }
    }

    /** D-213: every pair the static art adds clears its floor (.drafts/d213/art.py prints the same table). */
    @Test
    fun the_arts_pairs_clear_their_floors() {
        // D-219 (L15): Aurora's bar is the glass film (surface at 0.72 over bg on iOS, 0.94 here: both shells' films);
        // Norse and Vardr Labs are the solid surface.
        fun bars(set: ThemeSet) =
            if (set.theme == AppTheme.AURORA) listOf(0.72f, 0.94f).map { over(Tok.surface.of(set), it, Tok.bg.of(set)) }
            else listOf(Tok.surface.of(set))
        val floors = listOf(Tok.text to 7.0, Tok.textDim to 4.5, Tok.textFaint to 4.5, Tok.owed to 4.5,
            Tok.owing to 4.5, Tok.neutral to 4.5, Tok.watch to 4.5, Tok.accent to 4.5)
        for (stop in listOf("norseCardTop", "norseCardBottom")) {
            for ((tok, floor) in floors) {
                val r = contrast(tok.of(ThemeSet.NORSE), ThemeArt.color(stop))
                assertTrue("norse ${tok.name} on $stop is $r", r >= floor)
            }
        }
        for (bar in bars(ThemeSet.NORSE)) assertTrue("norse gold on the bar", contrast(Tok.accent.of(ThemeSet.NORSE), bar) >= 3.0)
        for (set in listOf(ThemeSet.VARDR_LIGHT, ThemeSet.VARDR_DARK)) {
            for (bar in bars(set)) {
                val outline = over(Tok.ring.of(set), TabIndicator.outlineAlpha(set), bar)
                assertTrue("$set outline", contrast(outline, bar) >= 3.0)
                assertTrue("$set glyph", contrast(Tok.ring.of(set), bar) >= 3.0)
            }
            for (ground in listOf(Tok.bg, Tok.surface)) {
                assertTrue("$set outlined words on ${ground.name}", contrast(Tok.accent.of(set), ground.of(set)) >= 4.5)
            }
            assertTrue("$set destructive words", contrast(Tok.owed.of(set), Tok.bg.of(set)) >= 4.5)
            val mark = over(Tok.textDim.of(set), ThemeArt.m("frameAlpha"), Tok.surface.of(set))
            assertTrue("$set frame marks", contrast(mark, Tok.surface.of(set)) >= 3.0)
            val dot = over(Tok.accent.of(set), ThemeArt.m("gridAlpha"), Tok.bg.of(set))
            assertTrue("$set text on a dot", contrast(Tok.text.of(set), dot) >= 7.0)
            assertTrue("$set textDim on a dot", contrast(Tok.textDim.of(set), dot) >= 4.5)
            // D-220 A2 (WORKLOG decision 7): accent text straight on the page ground holds 4.5 on the grid's darkest dot,
            // on 8-bit pixels (palette.py section A2: Vardr Labs light's accent 4.12 -> groundAccent #475B6B 4.52).
            fun q8(c: Color) = Color(Math.round(c.red * 255) / 255f, Math.round(c.green * 255) / 255f, Math.round(c.blue * 255) / 255f)
            assertTrue("$set groundAccent on a dot", contrast(q8(Tok.groundAccent.of(set)), q8(dot)) >= 4.5)
            // D-220 fix wave (Minor 12): measured on 8-bit pixels on the grounds too, as iOS's `contrast8`.
            for (ground in listOf(Tok.bg, Tok.surface, Tok.surfaceRaised)) {
                assertTrue("$set groundAccent on ${ground.name}", contrast(q8(Tok.groundAccent.of(set)), q8(ground.of(set))) >= 4.5)
            }
        }
        assertEquals("A2: the darker ground accent", "#475B6B", hex(Tok.groundAccent.of(ThemeSet.VARDR_LIGHT)))
        // D-220 fix wave (Minor 12): iOS's pin, Vardr Labs light's ground accent on its darkest dot at 4.52 on 8-bit pixels.
        fun q8(c: Color) = Color(Math.round(c.red * 255) / 255f, Math.round(c.green * 255) / 255f, Math.round(c.blue * 255) / 255f)
        val lightDot = over(Tok.accent.of(ThemeSet.VARDR_LIGHT), ThemeArt.m("gridAlpha"), Tok.bg.of(ThemeSet.VARDR_LIGHT))
        assertEquals(4.52, contrast(q8(Tok.groundAccent.of(ThemeSet.VARDR_LIGHT)), q8(lightDot)), 0.01)
        assertEquals("#9DB0BF", hex(Tok.groundAccent.of(ThemeSet.VARDR_DARK)))
        // D-219 (L13): Aurora's active pill is filled accent: 3:1 on each Aurora bar ground, and the onAccent glyph on it.
        for (set in listOf(ThemeSet.AURORA_LIGHT, ThemeSet.AURORA_DARK)) {
            for (bar in bars(set)) assertTrue("$set accent pill on the bar", contrast(Tok.accent.of(set), bar) >= 3.0)
            assertTrue("$set onAccent glyph on the pill", contrast(Tok.onAccent.of(set), Tok.accent.of(set)) >= 3.0)
        }
        // D-219: the tab labels (text when on, textDim when off) clear 4.5:1 on every set's bar ground.
        for (set in ThemeSet.entries) {
            for (bar in bars(set)) {
                assertTrue("$set text label on the bar", contrast(Tok.text.of(set), bar) >= 4.5)
                assertTrue("$set textDim label on the bar", contrast(Tok.textDim.of(set), bar) >= 4.5)
            }
        }
        val span = ThemeArt.m("diamondSide") * kotlin.math.sqrt(2f)
        for (t in listOf(0.5f - 14f / span, 0.5f + 14f / span)) {
            val under = over(ThemeArt.color("diamondBottom"), t, ThemeArt.color("diamondTop"))
            assertTrue("diamond at $t", contrast(Tok.onAccent.of(ThemeSet.NORSE), under) >= 3.0)
        }
        // The bolt's gradient runs from its start to its end unit point; the glyph box's extent along it is
        // t = 0.5 -/+ half / L (art.py: L = 91.43 dp, half = 21.86 dp for the 32 dp reticle in 72 x 64 at 150 degrees). Both ends
        // and every stop between them.
        val gx = (ThemeArt.m("boltEndX") - ThemeArt.m("boltStartX")).toDouble() * ThemeArt.m("boltWidth")
        val gy = (ThemeArt.m("boltEndY") - ThemeArt.m("boltStartY")).toDouble() * ThemeArt.m("boltHeight")
        val length = kotlin.math.hypot(gx, gy)
        val half = ThemeArt.m("reticleGlyph") / 2 * (kotlin.math.abs(gx) + kotlin.math.abs(gy)) / length
        assertEquals(91.43, length, 0.05)
        assertEquals(21.86, half, 0.05)
        val (lo, hi) = (0.5 - half / length) to (0.5 + half / length)
        var worst = Double.MAX_VALUE
        for ((set, dark) in listOf(ThemeSet.VARDR_DARK to true, ThemeSet.VARDR_LIGHT to false)) {
            val stops = ThemeArt.boltStops(dark)
            fun at(t: Double): Color {
                for ((a, b) in stops.zipWithNext()) {
                    if (t >= a.first && t <= b.first) return over(b.second, ((t - a.first) / (b.first - a.first)).toFloat(), a.second)
                }
                return stops.last().second
            }
            val under = listOf(at(lo), at(hi)) + stops.filter { it.first > lo && it.first < hi }.map { it.second }
            for (color in under) {
                val r = contrast(Tok.onAccent.of(set), color)
                worst = min(worst, r)
                assertTrue("$set bolt ${hex(color)} is $r", r >= 3.0)
            }
        }
        // D-219: art.py's worst (section 7, the 32 dp reticle) is Vardr Labs light at the glyph box's top end: 3.85
        // unrounded. Compose's sRGB Color holds 8 bits a channel, so the end colour here is art.py's printed #677B8C,
        // which measures 3.868 (recomputed in Python from #EEF1F4 on #677B8C).
        assertEquals("the worst: Vardr Labs light, the glyph box's top end (#677B8C)", 3.868, worst, 0.005)
    }

    /** D-214: the brightest pixel the moving art can reach, each part at its strongest over each ground stop; the twin
     *  of iOS's testTheMovingArtKeepsEveryFloorItCovers. The founder's 8-bit rule (2026-10-15): the floors hold on
     *  8-bit pixels, so every composite and every text colour is rounded to 1/255 a channel before the ratio
     *  (motion.py's `q8`; at 0.80 the Norse page's peak silver rune read 4.4987 against textFaint, hence 0.79,
     *  BUILD-LOG D-214). The curtains stacked as drawn: the_stacked_curtains_keep_the_aurora_page_floors. */
    @Test
    fun the_moving_art_keeps_every_floor_it_covers() {
        fun rgb(c: Color) = doubleArrayOf(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble())
        fun lum(c: DoubleArray): Double {
            fun ch(x: Double): Double {
                val v = Math.round(x * 255) / 255.0
                return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
        }
        fun ratio(a: DoubleArray, b: DoubleArray) = (max(lum(a), lum(b)) + 0.05) / (min(lum(a), lum(b)) + 0.05)
        fun blend(fg: DoubleArray, a: Double, g: DoubleArray, mode: (Double, Double) -> Double) =
            DoubleArray(3) { g[it] + (mode(fg[it], g[it]) - g[it]) * a }
        val normal = { x: Double, _: Double -> x }
        val screen = { x: Double, y: Double -> 1 - (1 - x) * (1 - y) }
        val multiply = { x: Double, y: Double -> x * y }
        fun stopRgb(s: CurtainStop) = doubleArrayOf(s.r / 255.0, s.g / 255.0, s.b / 255.0)
        val night = listOf(0xFF03070D, 0xFF071321, 0xFF0A1B26).map { rgb(Color(it)) }
        val dawn = listOf(0xFFD2F0E5, 0xFFE1F1EC, 0xFFEEF5F3).map { rgb(Color(it)) }
        fun curtainComposites(grounds: List<DoubleArray>, isNight: Boolean, k: Double): List<DoubleArray> =
            Curtains.SPECS.flatMap { spec ->
                spec.stops.flatMap { stop ->
                    grounds.map { g ->
                        blend(stopRgb(stop), stop.alpha * (if (isNight) spec.nightOpacity else spec.lightOpacity) * k, g, if (isNight) screen else multiply)
                    }
                }
            }
        fun starComposites(grounds: List<DoubleArray>, k: Double): List<DoubleArray> =
            Stars.ALL.flatMap { s -> grounds.map { blend(doubleArrayOf(1.0, 1.0, 1.0), s.alpha * k, it, normal) } }
        fun strength(key: String) = Strength.METRICS.getValue(key)

        // Aurora pages: text 7:1; labels, groundFaint and groundAccent 4.5:1 (the founder, 2026-10-15).
        val darkPage = curtainComposites(night, true, strength("auroraDarkPageCurtains")) +
            starComposites(night, strength("auroraDarkPageStars"))
        val lightPage = curtainComposites(dawn, false, strength("auroraLightPageCurtains")) + dawn
        for ((set, grounds) in listOf(ThemeSet.AURORA_DARK to darkPage, ThemeSet.AURORA_LIGHT to lightPage)) {
            for (g in grounds) {
                assertTrue("$set text on the page art", ratio(rgb(Tok.text.of(set)), g) >= 7.0)
                for (t in listOf(Tok.textDim, Tok.groundFaint, Tok.groundAccent)) {
                    assertTrue("$set ${t.name} on the page art", ratio(rgb(t.of(set)), g) >= 4.5)
                }
            }
        }
        // Aurora heroes: full strength by the founder's decision (2026-10-15), not floored; pinned here so a change is seen.
        assertEquals(1.0f, Strength.of("auroraHeroCurtains"), 0f)
        assertEquals(1.0f, Strength.of("auroraHeroStars"), 0f)
        // Norse page (D-226, .drafts/d226/art.py section 48): the ground is bg and each glow's brightest on-screen pixel
        // (the screen point nearest the ellipse's centre, the alpha falling to clear at `clearAt` of the radius: gold
        // 0.09 at the top centre (D-242; 0.08 before), silver 0.07 x (1 - 0.25 / 0.60) = 0.0408 at the bottom), and a gold and a silver rune
        // at their peak over each; the twin of iOS's. Faint words on the Norse page ground take groundFaint (D-226):
        // textFaint leaves this block (a rune over the gold peak leaves it at 4.17; 3.91 before D-242; art.py section 48).
        val bg = rgb(Tok.bg.of(ThemeSet.NORSE))
        val glowPeaks = NorseGlow.SPECS.map { s ->
            val px = s.centreX.coerceIn(0.0, 1.0)
            val py = s.centreY.coerceIn(0.0, 1.0)
            val d = Math.hypot((s.centreX - px) / s.radiusX, (s.centreY - py) / s.radiusY)
            s.alpha * max(0.0, 1 - d / s.clearAt) * strength("norsePageGlow")
        }
        assertEquals(2, glowPeaks.size)
        assertEquals("the gold glow's peak", 0.09, glowPeaks.first(), 1e-9)
        assertEquals("the silver glow's peak", 0.0408, glowPeaks.last(), 1e-4)
        val norseGrounds = listOf(bg) + NorseGlow.SPECS.zip(glowPeaks).map { (s, a) -> blend(rgb(s.color), a, bg, normal) }
        val norsePage = norseGrounds + norseGrounds.flatMap { g ->
            listOf(ArtDraw.RUNE_GOLD, ArtDraw.RUNE_SILVER).map { blend(rgb(it), RuneDrift.PEAK * strength("norsePageRunes"), g, normal) }
        }
        for (g in norsePage) {
            assertTrue("norse text on the page art", ratio(rgb(Tok.text.of(ThemeSet.NORSE)), g) >= 7.0)
            for (t in listOf(Tok.textDim, Tok.accent, Tok.groundFaint, Tok.groundAccent)) {
                val r = ratio(rgb(t.of(ThemeSet.NORSE)), g)
                assertTrue("norse ${t.name} on the page art is $r", r >= 4.5)
            }
        }
        // The worst of each, pinned beside Aurora's (art.py section 48, 8-bit).
        for ((t, want) in listOf(Tok.text to 11.12, Tok.textDim to 5.66, Tok.groundFaint to 5.66, Tok.groundAccent to 6.62)) {
            assertEquals("norse ${t.name} on the page art: art.py's value", want, norsePage.minOf { ratio(rgb(t.of(ThemeSet.NORSE)), it) }, 0.01)
        }
        // Norse hero: the watermark and the frame. S1 (fix wave 3): the hero's dim line is a fixed colour, onHero at
        // 0.82 over the hero's first stop (motion.py's `over(on, 0.82, stops[0])`), measured against the art.
        val norseOnHero = rgb(Tok.onHero.of(ThemeSet.NORSE))
        val norseDim = blend(norseOnHero, 0.82, rgb(Tok.heroStart.of(ThemeSet.NORSE)), normal)
        for (stop in listOf(Tok.heroStart, Tok.heroMid, Tok.heroEnd)) {
            val g = rgb(stop.of(ThemeSet.NORSE))
            for (a in listOf(0.16, 0.25)) {
                val art = blend(rgb(Tok.accent.of(ThemeSet.NORSE)), a * strength("norseHero"), g, normal)
                assertTrue("norse hero text on the art at $a", ratio(norseOnHero, art) >= 7.0)
                assertTrue("norse hero dim text on the art at $a", ratio(norseDim, art) >= 4.5)
            }
        }
        // D-243 (.drafts/d214/motion.py section 5, 1c, 1c', 1c''): the art at the top of its glow, the twin of iOS's. A page
        // rune at the top of its flare (RuneGlow.PAGE_FLARE) over its halo (RuneGlow.HALO) over each Norse ground, gold and
        // silver; a hero rune at HERO_FLARE over its halo over each hero stop; the ᚠ at its glow (WatermarkGlow.PEAK) over
        // its halo. The worst of each pinned to motion.py's printout (8-bit); textDim's headroom is 0.034.
        val runeColours = listOf(ArtDraw.RUNE_GOLD, ArtDraw.RUNE_SILVER).map { rgb(it) }
        fun flaring(core: Double, grounds: List<DoubleArray>) =
            grounds.flatMap { g -> runeColours.map { blend(it, core, blend(it, RuneGlow.HALO, g, normal), normal) } }
        val flarePage = flaring(RuneGlow.PAGE_FLARE, norseGrounds)
        for ((t, floor, want) in listOf(Triple(Tok.text, 7.0, 8.904), Triple(Tok.textDim, 4.5, 4.534), Triple(Tok.groundFaint, 4.5, 4.534),
            Triple(Tok.accent, 4.5, 5.300), Triple(Tok.groundAccent, 4.5, 5.300))) {
            val worst = flarePage.minOf { ratio(rgb(t.of(ThemeSet.NORSE)), it) }
            assertTrue("norse ${t.name} over a flaring rune is $worst", worst >= floor)
            assertEquals("norse ${t.name} over a flaring rune: motion.py's value", want, worst, 0.002)
        }
        // The negative: a flare a hundredth over the cap (0.20) leaves the labels under their floor (motion.py: 4.41).
        val tooBright = flaring(RuneGlow.PAGE_FLARE + 0.01, norseGrounds).minOf { ratio(rgb(Tok.textDim.of(ThemeSet.NORSE)), it) }
        assertTrue("a flare over the cap fails textDim: $tooBright", tooBright < 4.5)
        assertEquals(4.41, tooBright, 0.002)
        // D-243 (T7, motion.py 1f, plan test 9): Norse's card-edge glow (accent at a band's alpha) over a flaring rune, page
        // text beside a card, the twin of iOS's. Every band fails textDim (3.872 at the first, 4.469 at the last, 5.75 dp),
        // so the CLEAR DISTANCE is the glow's whole reach, 6 dp (sigma 3.0, .drafts/d243/motion-t7.out): there no band is left and the pixel is the flaring rune
        // alone (4.534, passing). Page-ground text keeps 6 dp from a Norse card (.drafts/d243/gap-audit.md).
        val gold = rgb(Tok.accent.of(ThemeSet.NORSE))
        fun banded(alpha: Double) = flarePage.minOf { ratio(rgb(Tok.textDim.of(ThemeSet.NORSE)), blend(gold, alpha, it, normal)) }
        val bands = ArtGeometry.glowBands()
        for ((d, a) in bands) assertTrue("the band at $d dp fails textDim", banded(a) < 4.5)
        assertEquals("the first band: motion.py's value", 3.872, banded(bands.first().second), 0.002)
        assertEquals("the band before the clear distance", 4.469, banded(bands.last().second), 0.002)
        assertTrue("at the clear distance", banded(0.0) >= 4.5)
        assertEquals(4.534, banded(0.0), 0.002)
        assertEquals(bands.size * ThemeArt.m("norseEdgeGlowBand"), ArtGeometry.glowReach(), 0f)
        assertEquals("motion.py's CLEAR DISTANCE", 6f, ArtGeometry.glowReach(), 0f)
        val heroStops = listOf(Tok.heroStart, Tok.heroMid, Tok.heroEnd).map { rgb(it.of(ThemeSet.NORSE)) }
        val accent = rgb(Tok.accent.of(ThemeSet.NORSE))
        val fehu = heroStops.map { g ->
            blend(accent, WatermarkGlow.PEAK * strength("norseHero"), blend(accent, WatermarkGlow.HALO * strength("norseHero"), g, normal), normal)
        }
        for ((name, pixels, wants) in listOf(Triple("a hero rune", flaring(RuneGlow.HERO_FLARE, heroStops), 8.377 to 5.834),
            Triple("the glowing ᚠ", fehu, 7.681 to 5.349))) {
            val on = pixels.minOf { ratio(norseOnHero, it) }
            val dim = pixels.minOf { ratio(norseDim, it) }
            assertTrue("norse hero text over $name is $on", on >= 7.0)
            assertTrue("norse hero dim text over $name is $dim", dim >= 4.5)
            assertEquals("$name: motion.py's value", wants.first, on, 0.002)
            assertEquals("$name, dim: motion.py's value", wants.second, dim, 0.002)
        }
        // Vardr Labs heroes: a sign at its peak in the steel (accent) or cyan (ring): the field's own brightest alpha,
        // (0.16 + 0.5 x depth 1) x calm 1 = 0.66, read off CurrencyField over 200 s so a change there follows.
        val signPeak = CurrencyField.FLOATERS.maxOf { f -> (0..400).maxOf { CurrencyField.pose(f, it * 0.5).alpha } }
        assertEquals(0.66, signPeak, 1e-9)
        for ((set, key) in listOf(ThemeSet.VARDR_LIGHT to "vardrLightHeroSigns", ThemeSet.VARDR_DARK to "vardrDarkHeroSigns")) {
            val onHero = rgb(Tok.onHero.of(set))
            val dim = blend(onHero, 0.82, rgb(Tok.heroStart.of(set)), normal)
            for (stop in listOf(Tok.heroStart, Tok.heroMid, Tok.heroEnd)) {
                for (n in listOf(Tok.accent, Tok.ring)) {
                    val art = blend(rgb(n.of(set)), signPeak * strength(key), rgb(stop.of(set)), normal)
                    assertTrue("$set ${n.name} sign", ratio(onHero, art) >= 7.0)
                    assertTrue("$set ${n.name} sign, dim", ratio(dim, art) >= 4.5)
                }
            }
        }
    }

    /** D-214 (F2, the founder's 8-bit rule): the Aurora pages' floors with the three curtains STACKED as the renderer
     *  draws them, the twin of iOS's testTheStackedCurtainsKeepTheAuroraPageFloors: the engine's poses every second
     *  over 0..441 s, the transform about each curtain's centre (scale, then skew), the full colour stops interpolated
     *  premultiplied, the streaks at their mean, the night or the dawn (its ellipse included), each curtain composited
     *  source-over in order, 8-bit pixels. Grid: y 0.07..0.59 by 0.02, x 0..1 by 0.05, on a 402 x 874 page. An exact
     *  port of .drafts/d214/stack.py, its worst values pinned to that script's source-over printout (independent
     *  goldens, BUILD-LOG D-214). The arched hem is left out, as stack.py leaves it out: it only removes curtain area,
     *  and every curtain colour is lighter than the night ground and darker than the dawn, so a curtain only moves the
     *  ground toward the text and the hem can only raise a ratio (stack.py rerun with the hem: the same worst values). */
    @Test
    fun the_stacked_curtains_keep_the_aurora_page_floors() {
        fun rgb(c: Color) = doubleArrayOf(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble())
        fun lum8(c: DoubleArray): Double {
            fun ch(x: Double): Double {
                val v = Math.round(x * 255) / 255.0
                return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
        }
        fun over(fg: DoubleArray, a: Double, g: DoubleArray) = DoubleArray(3) { g[it] + (fg[it] - g[it]) * a }
        fun lerp(stops: List<Pair<Double, DoubleArray>>, f: Double): DoubleArray {
            for ((a, b) in stops.zipWithNext()) if (f >= a.first && f <= b.first) return over(b.second, (f - a.first) / (b.first - a.first), a.second)
            return stops.last().second
        }
        fun hex(v: Long) = rgb(Color(v))
        val night = listOf(0.0 to hex(0xFF03070D), 0.55 to hex(0xFF071321), 1.0 to hex(0xFF0A1B26))
        val dawn = listOf(0.0 to hex(0xFFE1F1EC), 0.55 to hex(0xFFEEF5F3), 1.0 to hex(0xFFEEF5F3))
        val glow = hex(0xFFD2F0E5)
        fun ground(isNight: Boolean, x: Double, y: Double): DoubleArray {
            if (isNight) return lerp(night, y)
            val base = lerp(dawn, y)
            // The dawn's ellipse: centred (0.5, -0.10), radii (1.20, 0.60), the glow at its centre to clear at 0.70.
            val d = Math.sqrt(((x - 0.5) / 1.20).pow(2) + ((y + 0.10) / 0.60).pow(2)) / 0.70
            return if (d < 1) over(glow, max(0.0, 1 - d), base) else base
        }
        val none = DoubleArray(3)
        /** Colour and alpha at f down a curtain, interpolated premultiplied (as Skia and Core Graphics do). */
        fun stop(stops: List<CurtainStop>, f: Double): Pair<DoubleArray, Double> {
            for ((s0, s1) in stops.zipWithNext()) {
                if (f < s0.at || f > s1.at) continue
                val u = (f - s0.at) / (s1.at - s0.at)
                val a = s0.alpha + (s1.alpha - s0.alpha) * u
                if (a <= 0) return none to 0.0
                fun pm(x0: Int, x1: Int) = (x0 / 255.0 * s0.alpha + (x1 / 255.0 * s1.alpha - x0 / 255.0 * s0.alpha) * u) / a
                return doubleArrayOf(pm(s0.r, s1.r), pm(s0.g, s1.g), pm(s0.b, s1.b)) to a
            }
            return none to 0.0
        }
        val w = 402.0
        val h = 874.0
        val xs = (0..20).map { it * 0.05 }
        val ys = (0 until 27).map { 0.07 + it * 0.02 }
        assertEquals("the Look Book's streak pattern averages 0.60", 0.60, Curtains.STREAK_MEAN, 1e-12)
        val names = listOf(Tok.text, Tok.textDim, Tok.groundFaint, Tok.groundAccent)
        fun walk(set: ThemeSet): Map<Tok, Double> {
            val isNight = set.isDark
            val k = Strength.METRICS.getValue(if (isNight) "auroraDarkPageCurtains" else "auroraLightPageCurtains")
            val tokenLum = names.map { lum8(rgb(it.of(set))) }
            val grounds = ys.map { y -> xs.map { x -> ground(isNight, x, y) } }
            val worst = DoubleArray(names.size) { Double.POSITIVE_INFINITY }
            for (ti in 0 until 442) {
                val cs = Curtains.SPECS.indices.map { i ->
                    val p = Curtains.pose(i, ti.toDouble())
                    val cw = p.width * w
                    val ch = p.height * h
                    val opacity = if (isNight) Curtains.SPECS[i].nightOpacity else Curtains.SPECS[i].lightOpacity
                    doubleArrayOf(p.x * w + cw / 2, p.y * h + ch / 2, kotlin.math.tan(p.skewDegrees * Math.PI / 180), p.sx, p.sy, cw, ch,
                        Curtains.STREAK_MEAN * opacity * k)
                }
                for ((yi, y) in ys.withIndex()) for ((xi, x) in xs.withIndex()) {
                    var col = grounds[yi][xi]
                    for ((i, c) in cs.withIndex()) {
                        // Back through the curtain's transform about its centre: the scale, then the skew.
                        val u = x * w - c[0]
                        val v = y * h - c[1]
                        val ly = v / c[4]
                        val lx = (u - c[2] * c[3] * ly) / c[3]
                        if (kotlin.math.abs(lx) > c[5] / 2 || kotlin.math.abs(ly) > c[6] / 2) continue
                        val fy = (ly + c[6] / 2) / c[6]
                        val (colour, alpha) = stop(Curtains.SPECS[i].stops, fy)
                        if (alpha > 0) col = over(colour, alpha * c[7], col)
                    }
                    val l = lum8(col)
                    for ((n, tl) in tokenLum.withIndex()) worst[n] = min(worst[n], (max(l, tl) + 0.05) / (min(l, tl) + 0.05))
                }
            }
            return names.zip(worst.toList()).toMap()
        }
        // stack.py's source-over printout (.drafts/d214/stack-test.out), 8-bit: the port's goldens.
        val goldens = mapOf(
            ThemeSet.AURORA_DARK to mapOf(Tok.text to 11.68, Tok.textDim to 5.24, Tok.groundFaint to 5.24, Tok.groundAccent to 8.25),
            ThemeSet.AURORA_LIGHT to mapOf(Tok.text to 12.62, Tok.textDim to 4.71, Tok.groundFaint to 4.71, Tok.groundAccent to 4.71),
        )
        val floors = mapOf(Tok.text to 7.0, Tok.textDim to 4.5, Tok.groundFaint to 4.5, Tok.groundAccent to 4.5)
        for (set in listOf(ThemeSet.AURORA_DARK, ThemeSet.AURORA_LIGHT)) {
            val worst = walk(set)
            for ((tok, floor) in floors) {
                assertEquals("$set ${tok.name}: stack.py's worst", goldens.getValue(set).getValue(tok), worst.getValue(tok), 0.01)
                assertTrue("$set ${tok.name} on the stacked curtains is ${worst.getValue(tok)}", worst.getValue(tok) >= floor)
            }
        }
    }

    @Test
    fun stored_values_this_build_does_not_know_read_as_the_defaults() {
        assertEquals(AppTheme.AURORA, AppTheme.parse(null))
        assertEquals(AppTheme.AURORA, AppTheme.parse("night"))
        assertEquals(AppTheme.NORSE, AppTheme.parse("norse"))
        assertEquals(listOf("Aurora", "Norse", "Vardr Labs"), AppTheme.entries.map { it.label })
        assertEquals("D-232: a new install opens in Dark", AppearanceMode.DARK, AppearanceMode.parse(null))
        assertEquals("D-232: an unknown word reads as the default, Dark", AppearanceMode.DARK, AppearanceMode.parse("sepia"))
        assertEquals("Night is retired for now: it reads as Dark", AppearanceMode.DARK, AppearanceMode.parse("night"))
        assertEquals(listOf("System", "Light", "Dark"), AppearanceMode.entries.map { it.label })
    }

    /** D-232 (decisions 73, 75): nothing stored, or a word this build does not know, reads as Dark; the three words read
     *  as themselves; a stored `night` still reads as Dark. The twin of iOS's testANewInstallReadsDark. */
    @Test
    fun a_new_install_reads_dark() {
        assertEquals("nothing stored", AppearanceMode.DARK, AppearanceMode.parse(null))
        assertEquals("a word this build does not know", AppearanceMode.DARK, AppearanceMode.parse("bogus"))
        assertEquals("an empty word", AppearanceMode.DARK, AppearanceMode.parse(""))
        assertEquals("case matters: the words are stored lower case", AppearanceMode.DARK, AppearanceMode.parse("System"))
        assertEquals("Night is retired: it reads as Dark", AppearanceMode.DARK, AppearanceMode.parse("night"))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.parse("system"))
        assertEquals(AppearanceMode.LIGHT, AppearanceMode.parse("light"))
        assertEquals(AppearanceMode.DARK, AppearanceMode.parse("dark"))
    }

    /** D-232 (decision 74): the launch stores `system` only where nothing is stored and the vault file exists; a
     *  brand-new install stores nothing; a stored word is never touched. The twin of iOS's
     *  testTheLaunchMigrationKeepsAnExistingInstallOnSystem (same table). */
    @Test
    fun a_launch_keeps_an_existing_install_on_system() {
        val table = listOf<Triple<String?, Boolean, String?>>(
            Triple(null, true, "system"), Triple(null, false, null),
            Triple("light", true, null), Triple("light", false, null),
            Triple("dark", true, null), Triple("system", true, null),
            Triple("night", true, null), Triple("bogus", true, null),
        )
        for ((stored, vault, want) in table) assertEquals("$stored, vault $vault", want, AppearanceMode.migrate(stored, vault))
        assertEquals("modeSettled", AppearanceMode.SETTLED_KEY)
    }

    /** D-232: the twins. Appearance.swift's parse falls back to Dark on all three branches, its migrate stores `system` on
     *  the same condition, and its settled key is "appearance." + Android's (the existing key pattern). */
    @Test
    fun the_dark_default_matches_ios() {
        val swift = java.io.File("../../ios/Skipti/App/Appearance.swift").readText()
        fun body(anchor: String): String {
            assertTrue("Appearance.swift has `$anchor`", anchor in swift)
            return swift.substringAfter(anchor).substringBefore("\n    }\n").trim().lines().joinToString("\n") { it.trim() }
        }
        assertEquals(
            "guard let raw else { return .dark }\nif raw == \"night\" { return .dark }\nreturn AppearanceMode(rawValue: raw) ?? .dark",
            body("static func parse(_ raw: String?) -> AppearanceMode {"),
        )
        assertEquals(
            "stored == nil && vaultExists ? AppearanceMode.system.rawValue : nil",
            body("static func migrate(stored: String?, vaultExists: Bool) -> String? {"),
        )
        assertEquals(
            "appearance." + AppearanceMode.SETTLED_KEY,
            Regex("""static let settledKey = "([^"]*)"""").find(swift)?.groupValues?.get(1),
        )
        // D-244 (M3): the migrated mark, the same pattern, set with the migrated mode on both shells.
        assertEquals(
            "appearance." + AppearanceMode.MIGRATED_KEY,
            Regex("""static let migratedKey = "([^"]*)"""").find(swift)?.groupValues?.get(1),
        )
        assertTrue(
            "            defaults.set(value, forKey: AppearanceMode.storageKey)\n            defaults.set(true, forKey: AppearanceMode.migratedKey)\n" in swift,
        )
    }

    /** D-232 Task 3: the launch's wiring over a map-backed prefs (no Robolectric). A new install stores nothing (reads
     *  Dark) and stays that way at its second launch, when its vault exists; an install that ran before and never chose
     *  stores `system`; a stored choice is kept. Every first launch leaves the settled mark. iOS's twin:
     *  testTheLaunchSettlesTheDefaultOnce. */
    @Test
    fun the_launch_settles_the_default_once() {
        val fresh = FakePrefs()
        Appearance.settleDefault(fresh, vaultExists = false)
        assertEquals("a new install stores nothing", null, fresh.getString("mode", null))
        assertTrue("the first launch leaves the mark", fresh.getBoolean(AppearanceMode.SETTLED_KEY, false))
        assertFalse("D-244 M3: nothing migrated", fresh.contains(AppearanceMode.MIGRATED_KEY))
        assertEquals("and opens in Dark", AppearanceMode.DARK, AppearanceMode.parse(fresh.getString("mode", null)))
        Appearance.settleDefault(fresh, vaultExists = true)
        assertEquals("the second launch of a new install is still Dark", null, fresh.getString("mode", null))

        val upgraded = FakePrefs()
        Appearance.settleDefault(upgraded, vaultExists = true)
        assertEquals("an existing install keeps following the phone", "system", upgraded.getString("mode", null))
        assertTrue(upgraded.getBoolean(AppearanceMode.SETTLED_KEY, false))
        assertTrue("D-244 M3: the stored system is marked migrated", upgraded.getBoolean(AppearanceMode.MIGRATED_KEY, false))

        val chose = FakePrefs().also { it.map["mode"] = "light" }
        Appearance.settleDefault(chose, vaultExists = true)
        assertEquals("light", chose.getString("mode", null))
        assertTrue(chose.getBoolean(AppearanceMode.SETTLED_KEY, false))
        assertFalse("a chosen mode is never marked migrated", chose.contains(AppearanceMode.MIGRATED_KEY))
    }

    /** D-232 (decision 74, the T3 review): the mark is written synchronously, with `commit()`, in one edit with the
     *  mode. `apply()` writes later: a process killed in between would lose the mark, and the next launch (vault now
     *  present, nothing stored) would store `system` and turn a new install from Dark to System. A settled install
     *  writes nothing at all. */
    @Test
    fun the_settled_mark_is_committed_not_applied() {
        for ((stored, vault) in listOf(null to false, null to true, "light" to true)) {
            val prefs = FakePrefs().also { p -> stored?.let { p.map["mode"] = it } }
            Appearance.settleDefault(prefs, vaultExists = vault)
            assertEquals("one synchronous write (stored=$stored, vault=$vault)", 1, prefs.commits)
            assertEquals("no asynchronous write (stored=$stored, vault=$vault)", 0, prefs.applies)
            Appearance.settleDefault(prefs, vaultExists = true)
            assertEquals("a settled install writes nothing", 1, prefs.commits + prefs.applies)
        }
    }

    /** Order matters (decision 74): settling the default is the first statement after super.onCreate, before Firebase,
     *  the `--emulator-as` sign-in (whose IO coroutine opens the vault) and Appearance.load; it is called nowhere else;
     *  and the Context overload asks only whether AppRoot's vault file exists. */
    @Test
    fun the_default_is_settled_first_at_launch() {
        fun kt(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
        val root = kt("AppRoot.kt")
        val anchor = "override fun onCreate(savedInstanceState: Bundle?) {"
        assertTrue("AppRoot.kt has `$anchor`", anchor in root)
        val create = root.substringAfter(anchor).substringBefore("\n    }\n")
        val lines = create.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("super.onCreate(savedInstanceState)", lines[0])
        assertEquals("Appearance.settleDefault(applicationContext)", lines[1])
        assertTrue("the mode is read after", create.indexOf("Appearance.load(applicationContext)") > create.indexOf("Appearance.settleDefault("))
        val calls = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles()!!.filter { it.name.endsWith(".kt") }
            .sumOf { it.readText().split("Appearance.settleDefault(").size - 1 }
        assertEquals("settled once, from MainActivity.onCreate", 1, calls)
        val appearance = kt("Appearance.kt")
        assertTrue("the launch's vault file", "private const val VAULT_FILE = \"ledger-vault.db\"" in appearance)
        assertTrue("asked for existence only", "vaultExists = File(context.filesDir, VAULT_FILE).exists()" in appearance)
        assertTrue("the file AppRoot opens", "File(context.filesDir, \"ledger-vault.db\")" in root)
        // D-244 (ADR-0043), amended on purpose: the same key, named MODE and internal, so SettingsSync's table names it.
        assertTrue("the mode's key", "internal const val MODE = \"mode\"" in appearance)
    }

    /** D-232 (decision 77): the launch frame follows the phone, because nothing can read the app's stored choice before
     *  the app runs. The window theme is the app's own `Theme.Skipti`, Material Light without an action bar in
     *  `values` and Material (dark) without one in `values-night` (minSdk 26, before the platform's DayNight theme
     *  exists at API 29), and no activity sets a theme of its own over it. */
    @Test
    fun the_launch_frame_follows_the_phone() {
        val android = "http://schemas.android.com/apk/res/android"
        fun xml(path: String) = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(java.io.File(path))
        val manifest = xml("src/main/AndroidManifest.xml")
        val app = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("@style/Theme.Skipti", app.getAttributeNS(android, "theme"))
        val activities = manifest.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val a = activities.item(i) as org.w3c.dom.Element
            assertFalse("${a.getAttributeNS(android, "name")} sets no theme of its own", a.hasAttributeNS(android, "theme"))
        }
        fun parentIn(dir: String): String? {
            val styles = xml("src/main/res/$dir/themes.xml").getElementsByTagName("style")
            return (0 until styles.length).map { styles.item(it) as org.w3c.dom.Element }
                .singleOrNull { it.getAttribute("name") == "Theme.Skipti" }?.getAttribute("parent")
        }
        assertEquals("a light phone", "@android:style/Theme.Material.Light.NoActionBar", parentIn("values"))
        assertEquals("a dark phone", "@android:style/Theme.Material.NoActionBar", parentIn("values-night"))
    }

    /** D-232 (decision 78): the night mode Android 12+ is told, every mode in every theme. Values re-derived from the
     *  platform's documented constants (MODE_NIGHT_AUTO 0, MODE_NIGHT_NO 1, MODE_NIGHT_YES 2), not read back. Norse is
     *  always dark whatever the stored mode; System is AUTO, which clears the override so the phone reaches the app. */
    @Test
    fun the_night_mode_told_follows_the_apps_choice() {
        assertEquals(0, android.app.UiModeManager.MODE_NIGHT_AUTO)
        assertEquals(1, android.app.UiModeManager.MODE_NIGHT_NO)
        assertEquals(2, android.app.UiModeManager.MODE_NIGHT_YES)
        val table = mapOf(
            (AppearanceMode.SYSTEM to AppTheme.AURORA) to 0, (AppearanceMode.LIGHT to AppTheme.AURORA) to 1,
            (AppearanceMode.DARK to AppTheme.AURORA) to 2,
            (AppearanceMode.SYSTEM to AppTheme.VARDR) to 0, (AppearanceMode.LIGHT to AppTheme.VARDR) to 1,
            (AppearanceMode.DARK to AppTheme.VARDR) to 2,
            (AppearanceMode.SYSTEM to AppTheme.NORSE) to 2, (AppearanceMode.LIGHT to AppTheme.NORSE) to 2,
            (AppearanceMode.DARK to AppTheme.NORSE) to 2,
        )
        assertEquals("every mode x every theme", AppearanceMode.entries.size * AppTheme.entries.size, table.size)
        for ((key, want) in table) assertEquals("$key", want, Appearance.nightModeFor(key.first, key.second))
        assertEquals("a new install (nothing stored) is told YES", 2,
            Appearance.nightModeFor(AppearanceMode.parse(null), AppTheme.parse(null)))
    }

    /** D-232 (decision 78): the system is told only a value this install has not told it. Telling makes the activity
     *  again; the activity made again finds the record equal and tells nothing, so a launch never loops. */
    @Test
    fun the_night_mode_is_told_only_when_it_differs() {
        assertEquals("never told: tell", 2, Appearance.nightModeToTell(2, null))
        assertEquals("never told, AUTO: tell once", 0, Appearance.nightModeToTell(0, null))
        assertEquals("changed: tell", 0, Appearance.nightModeToTell(0, 2))
        assertEquals("changed: tell", 1, Appearance.nightModeToTell(1, 2))
        for (wanted in 0..2) {
            assertEquals("already told $wanted: nothing", null, Appearance.nightModeToTell(wanted, wanted))
            val told = Appearance.nightModeToTell(wanted, null)
            assertEquals("the activity made again tells nothing", null, Appearance.nightModeToTell(wanted, told))
        }
    }

    /** D-232 (decision 78): the one call to `setApplicationNightMode` is behind `Build.VERSION.SDK_INT >= 31` (the API
     *  exists from 31; below it nothing changes) and behind the differs check, and the app tells at launch right after
     *  settling the default, and whenever the mode or the theme changes. */
    @Test
    fun the_night_mode_is_told_on_android_12_and_later_and_only_when_it_differs() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val calls = dir.listFiles()!!.filter { it.name.endsWith(".kt") }.sumOf { it.readText().split("setApplicationNightMode(").size - 1 }
        assertEquals("one call", 1, calls)
        val appearance = java.io.File(dir, "Appearance.kt").readText()
        val body = appearance.substringAfter("fun tellNightMode(context: Context) {").substringBefore("\n    }\n")
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("the SDK guard first", "if (Build.VERSION.SDK_INT >= 31) {", lines[0])
        val guarded = body.substringAfter("if (Build.VERSION.SDK_INT >= 31) {").substringBefore("\n        }\n")
        val call = guarded.indexOf("uiModes.setApplicationNightMode(tell)")
        assertTrue("the call is inside the guard", call > 0)
        val differs = guarded.indexOf("val tell = nightModeToTell(wanted, told) ?: return")
        assertTrue("the differs check, before the call", differs in 1 until call)
        assertTrue("the record after the call", guarded.indexOf("putInt(NIGHT_TOLD, tell)") > call)
        fun fn(name: String) = appearance.substringAfter("fun $name(").substringBefore("\n    }\n")
        // D-244 (ADR-0043), amended on purpose: a mode or theme change goes through SettingsSync.set, which shows it through
        // Appearance.reload, the one place that tells for either (an arrival from the account takes the same path).
        assertTrue("a mode change goes through the funnel", "SettingsSync.set(context, SettingsSync.MODE, newMode.stored)" in fn("setMode"))
        assertTrue("a theme change goes through the funnel", "SettingsSync.set(context, SettingsSync.THEME, newTheme.stored)" in fn("setTheme"))
        assertTrue("a mode or theme change tells", "if (SettingsSync.THEME in keys || SettingsSync.MODE in keys) tellNightMode(context)" in fn("reload"))
        val root = java.io.File(dir, "AppRoot.kt").readText()
        val create = root.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {").substringBefore("\n    }\n")
        val launch = create.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("told at launch, right after the default is settled", "Appearance.tellNightMode(applicationContext)", launch[2])
    }

    /** Norse is always dark; the person's own choice is kept for later. */
    @Test
    fun norse_forces_dark_and_keeps_the_stored_mode() {
        assertTrue(AppearanceMode.LIGHT.isDark(systemDark = false, theme = AppTheme.NORSE))
        assertTrue(AppearanceMode.SYSTEM.isDark(systemDark = false, theme = AppTheme.NORSE))
        assertFalse(AppearanceMode.LIGHT.isDark(systemDark = true, theme = AppTheme.AURORA))
        assertTrue(AppearanceMode.SYSTEM.isDark(systemDark = true, theme = AppTheme.VARDR))
        Appearance.setForTest(theme = AppTheme.NORSE, mode = AppearanceMode.LIGHT)
        assertEquals("a light draw under Norse still draws Norse", "#07080E", hex(Tok.bg(false)))
        assertEquals(AppearanceMode.LIGHT, Appearance.mode)
    }

    @Test
    fun text_size_steps_move_the_phones_size_and_clamp_at_both_ends() {
        assertEquals(0, TextSize.parse(null))
        assertEquals(0, TextSize.parse(4))
        assertEquals(0, TextSize.parse(-3))
        assertEquals(-2, TextSize.parse(-2))
        assertEquals(3, TextSize.parse(3))
        assertEquals(1.0f, TextSize.fontScale(1.0f, 0), 0.0001f)
        assertEquals(0.88f, TextSize.fontScale(1.0f, -2), 0.0001f)
        assertEquals(1.35f, TextSize.fontScale(1.0f, 3), 0.0001f)
        assertEquals("clamped low", 0.75f, TextSize.fontScale(0.8f, -2), 0.0001f)
        assertEquals("clamped high", 2.0f, TextSize.fontScale(2.0f, 3), 0.0001f)
        val d = Appearance.density(Density(2f, 1.3f), 1)
        assertEquals(2f, d.density, 0.0001f)
        assertEquals(1.3f * 1.12f, d.fontScale, 0.0001f)
        assertEquals("Your phone's size", TextSize.accessibilityValue(0))
        assertEquals("1 step smaller", TextSize.accessibilityValue(-1))
        assertEquals("3 steps larger", TextSize.accessibilityValue(3))
    }

    /** D-165: one place builds chip colours; the selected pair clears 4.5:1 in every set. */
    @Test
    fun every_chip_takes_its_colours_from_one_place() {
        val sources = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!
        val builders = sources.filter { it.readText().contains("filterChipColors(") }.map { it.name }
        assertEquals(listOf("Tokens.kt"), builders)
        val tokens = java.io.File("src/main/java/com/vardrlabs/skipti/Tokens.kt").readText()
        assertTrue(tokens.contains("selectedLabelColor = Tok.onAccent(dark),"))
        for (set in ThemeSet.entries) assertTrue("$set", contrast(Tok.onAccent.of(set), Tok.accent.of(set)) >= 4.5)
    }

    /** D-212: every Switch takes its colours from switchColors (Tokens.kt). Material's own unchecked thumb and
     *  border are `outline`, the hairline since D-212 (1.06 to 1.39:1 on the track). The unchecked pair is dim
     *  text on the raised surface and the checked thumb is the page colour on the accent, 3:1 or better. */
    @Test
    fun every_switch_takes_its_colours_from_one_place() {
        val sources = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!
        val call = Regex("""(?<![A-Za-z0-9_])Switch\(""")
        var seen = 0
        for (f in sources) {
            val text = f.readText()
            val calls = call.findAll(text).count()
            seen += calls
            assertEquals("${f.name}: each Switch( passes colors = switchColors(dark)", calls, Regex("""colors = switchColors\(""").findAll(text).count())
            val builders = Regex("""SwitchDefaults\.colors\(""").findAll(text).count()
            assertEquals("${f.name}: SwitchDefaults.colors( belongs in Tokens.kt only", if (f.name == "Tokens.kt") 1 else 0, builders)
        }
        // D-225: the drawn ThemeSwitch replaced the Material switch on You's pages (it takes SwitchPaint, not these
        // colours); the floor counts both, so the scan never passes on nothing.
        val drawn = sources.sumOf { Regex("""(?<![A-Za-z0-9_])ThemeSwitch\(""").findAll(it.readText()).count() }
        assertTrue("the check found the app's switches ($seen Material, $drawn drawn)", seen + drawn >= 2)
        // D-225 fix wave (M12): and the drawn ones are really there: the switch rows of Haptics, Find me, Screen
        // privacy, Add expense's members and Itemised's lines (not only ThemeSwitch's own declaration).
        val toggles = sources.sumOf { Regex("""RowTrailing\.Toggle\(""").findAll(it.readText()).count() }
        assertTrue("the app's switch rows ($toggles)", toggles >= 5)
        for (set in ThemeSet.entries) {
            val p = switchPalette(set)
            val thumb = contrast(p.uncheckedThumb, p.uncheckedTrack)
            assertTrue("$set unchecked thumb on track: $thumb", thumb >= 3.0)
            val border = contrast(p.uncheckedBorder, p.uncheckedTrack)
            assertTrue("$set unchecked border on track: $border", border >= 3.0)
            val checked = contrast(p.checkedThumb, p.checkedTrack)
            assertTrue("$set checked thumb on track: $checked", checked >= 3.0)
        }
    }

    /** D-225 (Design 3; L-D217-9): a switch row is ONE toggleable node with the Switch role (TalkBack reads the row's
     *  words, "switch", and its state), the drawn switch is silent, and no Material switch is left where S2 drew its
     *  own: ListRows.kt, AccountHubScreen.kt (Haptics) and People.kt (Find me) draw `.toggle` rows. The twin of iOS's
     *  testNoSystemToggleIsLeft and the representation half of testTheSwitchIsDrawnAndAccessible. */
    @Test
    fun the_switch_row_is_one_toggleable() {
        fun kt(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
        val rows = kt("ListRows.kt")
        val row = rows.substringAfter("fun ListRow(").substringBefore("\n}\n")
        assertTrue(
            "the toggle row is toggleable with the Switch role",
            Regex("""is RowTrailing\.Toggle -> Modifier\.toggleable\(\s*value = trailing\.isOn,\s*enabled = enabled,\s*role = Role\.Switch,\s*onValueChange = trailing\.onChange,""").containsMatchIn(row),
        )
        val sw = rows.substringAfter("fun ThemeSwitch(").substringBefore("\n}\n")
        assertTrue("the drawn switch is silent", ".clearAndSetSemantics {}" in sw)
        val material = Regex("""material3\.Switch\b|(?<![A-Za-z0-9_.])Switch\(""")
        for (name in listOf("ListRows.kt", "AccountHubScreen.kt", "People.kt")) {
            assertFalse("$name still has a Material Switch", material.containsMatchIn(kt(name)))
            assertFalse("$name: onCheckedChange", "onCheckedChange" in kt(name))
        }
        val findMe = kt("People.kt").substringAfter("PeopleCopy.FINDABLE_TOGGLE,").substringBefore("PeopleCopy.FINDABLE_ON")
        assertTrue("Find me is a .toggle row", "trailing = RowTrailing.Toggle(findable) { on ->" in findMe)
        assertTrue("its write is today's", "People.setFindable(on, LedgerStore(vaultPath))" in findMe)
    }

    /** The label on every default Material button is readable on its fill in every set. */
    @Test
    fun a_primary_button_label_is_readable_in_every_set() {
        for (set in ThemeSet.entries) {
            Appearance.setForTest(theme = set.theme)
            val scheme = skiptiColorScheme(set.isDark)
            val ratio = contrast(scheme.onPrimary, scheme.primary)
            assertTrue("$set: $ratio", ratio >= 4.5)
        }
    }

    /** D-212: no Material baseline role shows through (the purple slider
     *  track, the dialog's lavender container): each role SKIPTI's screens
     *  can reach is the theme's own token, and a dialog's words read on it. */
    @Test
    fun every_material_role_is_the_themes_token_in_every_set() {
        for (set in ThemeSet.entries) {
            Appearance.setForTest(theme = set.theme)
            val s = skiptiColorScheme(set.isDark)
            fun t(tok: Tok) = tok.of(set)
            val roles = listOf(
                "background" to (s.background to t(Tok.bg)),
                "surface" to (s.surface to t(Tok.surface)),
                "primary" to (s.primary to t(Tok.accent)),
                "onPrimary" to (s.onPrimary to t(Tok.onAccent)),
                "onBackground" to (s.onBackground to t(Tok.text)),
                "onSurface" to (s.onSurface to t(Tok.text)),
                "onSurfaceVariant" to (s.onSurfaceVariant to t(Tok.textDim)),
                "surfaceVariant" to (s.surfaceVariant to t(Tok.surfaceRaised)),
                "surfaceContainerLowest" to (s.surfaceContainerLowest to t(Tok.surface)),
                "surfaceContainerLow" to (s.surfaceContainerLow to t(Tok.surface)),
                "surfaceContainer" to (s.surfaceContainer to t(Tok.surface)),
                "surfaceContainerHigh" to (s.surfaceContainerHigh to t(Tok.surfaceRaised)),
                "surfaceContainerHighest" to (s.surfaceContainerHighest to t(Tok.surfaceRaised)),
                "secondary" to (s.secondary to t(Tok.accent)),
                "onSecondary" to (s.onSecondary to t(Tok.onAccent)),
                "secondaryContainer" to (s.secondaryContainer to t(Tok.accentSoft)),
                "onSecondaryContainer" to (s.onSecondaryContainer to t(Tok.accent)),
                "primaryContainer" to (s.primaryContainer to t(Tok.accentSoft)),
                "onPrimaryContainer" to (s.onPrimaryContainer to t(Tok.accent)),
                "outline" to (s.outline to t(Tok.textFaint)),
                "outlineVariant" to (s.outlineVariant to t(Tok.hairline)),
                "error" to (s.error to t(Tok.owed)),
                "onError" to (s.onError to t(Tok.onAccent)),
            )
            for ((name, pair) in roles) assertEquals("$set $name", hex(pair.second), hex(pair.first))
            val high = s.surfaceContainerHigh
            assertTrue("$set: onSurface on a dialog", contrast(s.onSurface, high) >= 7.0)
            assertTrue("$set: onSurfaceVariant on a dialog", contrast(s.onSurfaceVariant, high) >= 4.5)
        }
    }

    /** D-212 (fix wave 3, F13): a text field's border is `outline`; a control's boundary needs 3:1 (WCAG 1.4.11)
     *  on both the page surface and the raised surface, in every set. Dividers keep the hairline (`outlineVariant`). */
    @Test
    fun a_field_border_reads_three_to_one_in_every_set() {
        for (set in ThemeSet.entries) {
            Appearance.setForTest(theme = set.theme)
            val s = skiptiColorScheme(set.isDark)
            val onSurface = contrast(s.outline, Tok.surface.of(set))
            val onRaised = contrast(s.outline, Tok.surfaceRaised.of(set))
            assertTrue("$set outline on surface: $onSurface", onSurface >= 3.0)
            assertTrue("$set outline on surfaceRaised: $onRaised", onRaised >= 3.0)
        }
    }

    /** Export opens from Settings, and Back returns there (D-143's rule). */
    @Test
    fun back_from_export_returns_to_settings() {
        assertEquals("account/data", HomeRoutes.parent("export")) // D-206: Your data
    }

    /** D-206: You is your picture first, then five groups in this order;
     *  and its sign-in is never the filled primary (D-128, GUARANTEES #5). */
    @Test
    fun settings_sections_come_in_the_founders_order() {
        assertEquals(
            listOf("Profile and account", "Appearance", "Security", "Your data", "About SKIPTI"),
            SettingsSection.entries.map { it.title },
        )
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt").readText()
        val hero = src.indexOf("AvatarView(avatar, name, 104.dp, dark)")
        // D-225: You's list is the shared ListCard (Design 1).
        val groups = src.indexOf("SettingsSection.entries.forEach {")
        assertTrue("the picture comes first: $hero, $groups", hero in 0 until groups)
        assertFalse("no filled accent button in Settings", src.contains("ButtonDefaults.buttonColors(containerColor = Tok.accent"))
    }

    /** D-220 (L69): Delete account is the Danger zone's action (the owe colour), as iOS's
     *  `SecondaryButtonStyle(destructive: true)`. */
    @Test
    fun delete_account_is_destructive() {
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt").readText()
        // D-225 T5 (Design 4): Delete account is a danger-tone row of Security's careful card, opening its page.
        val page = src.substringAfter("private fun SecurityPage(").substringBefore("\n}\n")
        val row = page.substringAfter("SettingItem.DELETE_ACCOUNT -> ListRow(").substringBefore("{ onOpenDelete() }")
        assertTrue(row, "PhonesCopy.DELETE_TITLE" in row && "tone = RowTone.DANGER" in row)
        assertFalse(src.contains("SecondaryAction(PhonesCopy.DELETE_TITLE"))
    }

    /** D-225 fix wave (accessibility; the look's uiautomator dump: a switch row reported `android.view.View`, and each
     *  choice row was a checkable View plus a second, stateless `android.widget.RadioButton` node): a row is ONE node.
     *  Its children are cleared (Compose splits a merging node's role into a fake child when it has children), the
     *  action's role and state stay on it (the action sits BEFORE the clearing modifier, so it survives the clear), and
     *  a switch row reports the Switch class. */
    @Test
    fun a_row_is_one_node_with_its_role_and_state() {
        assertEquals("android.widget.Switch", ListRows.className(RowTrailing.Toggle(true) {}))
        for (t in listOf(RowTrailing.Check(true), RowTrailing.Chevron, RowTrailing.None, RowTrailing.Value("INR", chevron = true))) {
            assertEquals("no class of our own for $t", null, ListRows.className(t))
        }
        val row = java.io.File("src/main/java/com/vardrlabs/skipti/ListRows.kt").readText().substringAfter("fun ListRow(").substringBefore("\n}\n")
        val action = row.indexOf(".then(action)")
        val clear = row.indexOf(".clearAndSetSemantics {\n")
        assertTrue("the action, then the clear: $action $clear", action in 0 until clear)
        val node = row.substring(clear).substringBefore("},")
        assertTrue("the words", "contentDescription = ListRows.spoken(title, parts, trailing)" in node)
        assertTrue("the class", "ListRows.className(trailing)?.let { accessibilityClassName = it }" in node)
        assertFalse("no second, merging node", ".semantics(mergeDescendants = true)" in row)
    }

    /** D-225 fix wave (F3, M8; the look: Norse's diamond overflowed Group settings' 30 slot and its tip sat about 1 from
     *  the chevron on both phones): a drawn piece is laid out in its 30 slot, and the tile is sized so its DRAWN side
     *  fits the slot (Norse's diamond 21, drawn 29.7), keeping Aurora's gap to the chevron. */
    @Test
    fun a_drawn_piece_sits_inside_its_slot() {
        assertEquals(30, ListRows.PIECE_SLOT)
        for (shape in TileShape.entries) {
            val tile = ListRows.pieceTile(shape)
            val drawn = tileDrawnSide(shape, tile)
            assertTrue("$shape drawn $drawn inside the slot", drawn <= ListRows.PIECE_SLOT)
            assertTrue("$shape as large as the slot allows ($tile)", tileDrawnSide(shape, tile + 1) > ListRows.PIECE_SLOT)
        }
        assertEquals("Norse's diamond", 21, ListRows.pieceTile(TileShape.DIAMOND))
        assertEquals("Aurora's rounded tile, as before", 30, ListRows.pieceTile(TileShape.ROUNDED))
        val rows = java.io.File("src/main/java/com/vardrlabs/skipti/ListRows.kt").readText()
        val end = rows.substringAfter("private fun RowEnd(").substringBefore("\n}\n")
        assertTrue("the slot", "Box(Modifier.size(ListRows.PIECE_SLOT.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { trailing.content() }" in end)
        val settings = java.io.File("src/main/java/com/vardrlabs/skipti/GroupSettings.kt").readText()
        assertTrue("Group settings' picture takes the slot's tile",
            "RowTrailing.Piece(chevron = true) { GroupTile(n.group, dark, ListRows.pieceTile(TileShape.of(Appearance.theme, person = false))) }" in settings)
    }

    /** D-225 fix wave (WORKLOG decision 26, review I3): every section label on the pages D-225 rebuilt takes `textDim`
     *  (SectionLabel's default; iOS's fixer moves its five `groundFaint` sites to it): no call there passes a colour of
     *  its own, and the default is `textDim`. */
    @Test
    fun the_settings_section_labels_take_text_dim() {
        val dir = "src/main/java/com/vardrlabs/skipti/"
        val decl = java.io.File(dir + "ThemeArt.kt").readText()
        assertTrue("the default", "fun SectionLabel(text: String, dark: Boolean, modifier: Modifier = Modifier, color: Color = Tok.textDim(dark)) {" in decl)
        val files = listOf("AddExpenseScreen.kt", "GroupScreens.kt", "AccountHubScreen.kt", "About.kt", "Profile.kt", "People.kt")
        var seen = 0
        for (name in files) {
            val text = java.io.File(dir + name).readText()
            for (m in Regex("""SectionLabel\(""").findAll(text)) {
                val call = text.substring(m.range.first, text.indexOf('\n', m.range.first))
                seen++
                assertFalse("$name: a section label with its own colour: $call", "color =" in call)
            }
        }
        assertTrue("the labels were found ($seen)", seen >= 15)
    }

    /** D-225 fix wave A3 (WORKLOG decision 26): the twin of iOS's walk of the whole app. Every `SectionLabel(` call in
     *  every Kotlin file of the app, its whole argument list (a call may span lines), takes `textDim` (the default): a
     *  section label names what follows. D-225 follow-up (M4, the re-review): an ALLOW-list, as iOS's is: the text,
     *  `dark`, an optional modifier, and no colour, or exactly `color = Tok.text(dark)` for the labels inside Home's
     *  cards, counted per file (8: HomeParts 1, Settled 2, OverviewTab 5; iOS counts 7 sites because Overview's "Your
     *  ledgers" and "People" share one Swift helper, OverviewScreen.swift's). Any other argument fails, a variable or a
     *  positional colour included. Parentheses and commas inside string literals are skipped. */
    @Test
    fun no_section_label_anywhere_takes_a_faint_colour() {
        val root = java.io.File("src/main/java/com/vardrlabs/skipti")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the app's files were found (${files.size})", files.size >= 50)
        var seen = 0
        val wrong = mutableListOf<String>()
        val inCard = mutableMapOf<String, Int>()
        for (f in files) {
            val text = f.readText()
            for (m in Regex("""(?<![A-Za-z.])SectionLabel\(""").findAll(text)) {
                if (text.substring(maxOf(0, m.range.first - 4), m.range.first) == "fun ") continue
                // The top-level arguments, split on commas at depth 1, string literals skipped.
                val args = mutableListOf<String>()
                val arg = StringBuilder()
                var depth = 0
                var quoted = false
                var i = m.range.last
                while (i < text.length) {
                    val ch = text[i]
                    if (quoted) {
                        if (ch == '\\') { arg.append(ch); i++; if (i < text.length) arg.append(text[i]); i++; continue }
                        if (ch == '"') quoted = false
                        arg.append(ch)
                    } else when (ch) {
                        '"' -> { quoted = true; arg.append(ch) }
                        '(' -> { if (depth > 0) arg.append(ch); depth++ }
                        ')' -> { depth--; if (depth == 0) break; arg.append(ch) }
                        ',' -> if (depth == 1) { args += arg.toString().trim(); arg.clear() } else arg.append(ch)
                        else -> arg.append(ch)
                    }
                    i++
                }
                if (arg.isNotBlank()) args += arg.toString().trim()
                seen++
                val call = "${f.name}: SectionLabel(${args.joinToString(", ")})"
                val rest = args.drop(2)
                val colour = rest.filter { it.startsWith("color =") }
                val modifiers = rest.filterNot { it.startsWith("color =") }
                val modifierOk = modifiers.size <= 1 && modifiers.all { it.startsWith("Modifier") || it.startsWith("modifier = Modifier") }
                val darkOk = args.getOrNull(1) == "dark"
                val colourOk = colour.isEmpty() || colour == listOf("color = Tok.text(dark)")
                if (!darkOk || !modifierOk || !colourOk) wrong += call
                if (colour.isNotEmpty() && colourOk) inCard[f.name] = (inCard[f.name] ?: 0) + 1
            }
        }
        assertTrue("the labels were found ($seen)", seen >= 45)
        assertEquals("section labels outside the allow-list: $wrong", emptyList<String>(), wrong)
        assertEquals("Home's in-card labels in text", mapOf("HomeParts.kt" to 1, "Settled.kt" to 2, "OverviewTab.kt" to 5), inCard)
    }

    // ---- D-216 Task 2: the app icon's decisions, names and copy (Design 3 to 5; the twin of iOS's AppearanceTests,
    // the same table written by hand from the Design, not read from the code) ----

    /** Design 3: the stored choice. Its words are permanent from D-216; nothing stored, an empty string, a word with
     *  another case and an unknown word all read Match theme. */
    @Test
    fun the_icon_choice_is_stored_as_its_word() {
        assertEquals("icon", AppIconChoice.STORAGE_KEY)
        assertEquals(listOf("match", "aurora", "norse", "vardr"), AppIconChoice.entries.map { it.stored })
        val parsed = listOf(
            null to AppIconChoice.MATCH, "" to AppIconChoice.MATCH, "Norse" to AppIconChoice.MATCH,
            "AURORA" to AppIconChoice.MATCH, "night" to AppIconChoice.MATCH, " norse" to AppIconChoice.MATCH,
            "match" to AppIconChoice.MATCH, "aurora" to AppIconChoice.AURORA, "norse" to AppIconChoice.NORSE,
            "vardr" to AppIconChoice.VARDR,
        )
        for ((raw, choice) in parsed) assertEquals("parse $raw", choice, AppIconChoice.parse(raw))
        assertEquals("Match theme unless the person chose", AppIconChoice.MATCH, Appearance.iconChoice)
        Appearance.setForTest(iconChoice = AppIconChoice.NORSE)
        assertEquals(AppIconChoice.NORSE, Appearance.iconChoice)
    }

    /** Design 1 to 3: three icons, one per theme, each a launcher alias of MainActivity (permanent names). */
    @Test
    fun the_icons_and_their_aliases() {
        assertEquals(listOf(AppIcon.AURORA, AppIcon.NORSE, AppIcon.VARDR), AppIcon.entries)
        assertEquals(listOf("aurora", "norse", "vardr"), AppIcon.entries.map { it.stored })
        assertEquals(
            listOf("com.vardrlabs.skipti.IconAurora", "com.vardrlabs.skipti.IconNorse", "com.vardrlabs.skipti.IconVardr"),
            AppIcon.entries.map { it.alias },
        )
        assertEquals(AppIcon.AURORA, AppIcon.of(AppTheme.AURORA))
        assertEquals(AppIcon.NORSE, AppIcon.of(AppTheme.NORSE))
        assertEquals(AppIcon.VARDR, AppIcon.of(AppTheme.VARDR))
        assertEquals("the themes' labels", listOf("Aurora", "Norse", "Vardr Labs"), AppIcon.entries.map { it.label })
        assertEquals(AppTheme.entries.map { it.label }, AppIcon.entries.map { it.label })
    }

    /** Design 3 to 5: which icon is wanted, when the phone changes it, which row is checked, and what a tap or the
     *  switch stores. */
    @Test
    fun the_icon_plan_goldens() {
        val target = listOf(
            Triple(AppIconChoice.MATCH, AppTheme.AURORA, AppIcon.AURORA), Triple(AppIconChoice.MATCH, AppTheme.NORSE, AppIcon.NORSE),
            Triple(AppIconChoice.MATCH, AppTheme.VARDR, AppIcon.VARDR),
            Triple(AppIconChoice.AURORA, AppTheme.AURORA, AppIcon.AURORA), Triple(AppIconChoice.AURORA, AppTheme.NORSE, AppIcon.AURORA),
            Triple(AppIconChoice.AURORA, AppTheme.VARDR, AppIcon.AURORA),
            Triple(AppIconChoice.NORSE, AppTheme.AURORA, AppIcon.NORSE), Triple(AppIconChoice.NORSE, AppTheme.NORSE, AppIcon.NORSE),
            Triple(AppIconChoice.NORSE, AppTheme.VARDR, AppIcon.NORSE),
            Triple(AppIconChoice.VARDR, AppTheme.AURORA, AppIcon.VARDR), Triple(AppIconChoice.VARDR, AppTheme.NORSE, AppIcon.VARDR),
            Triple(AppIconChoice.VARDR, AppTheme.VARDR, AppIcon.VARDR),
        )
        assertEquals("all 4 x 3 cells", 12, target.size)
        for ((choice, theme, icon) in target) {
            assertEquals("target $choice on $theme", icon, AppIconPlan.target(choice, theme))
            assertEquals("the check $choice on $theme", icon, AppIconPlan.checked(choice, theme))
        }
        val change = listOf(
            Triple(AppIcon.AURORA, AppIcon.AURORA, null), Triple(AppIcon.AURORA, AppIcon.NORSE, AppIcon.AURORA),
            Triple(AppIcon.AURORA, AppIcon.VARDR, AppIcon.AURORA),
            Triple(AppIcon.NORSE, AppIcon.AURORA, AppIcon.NORSE), Triple(AppIcon.NORSE, AppIcon.NORSE, null),
            Triple(AppIcon.NORSE, AppIcon.VARDR, AppIcon.NORSE),
            Triple(AppIcon.VARDR, AppIcon.AURORA, AppIcon.VARDR), Triple(AppIcon.VARDR, AppIcon.NORSE, AppIcon.VARDR),
            Triple(AppIcon.VARDR, AppIcon.VARDR, null),
        )
        assertEquals("all 9 pairs", 9, change.size)
        for ((wanted, current, result) in change) {
            assertEquals("change to $wanted from $current", result, AppIconPlan.change(wanted, current))
        }
        assertEquals(AppIconChoice.AURORA, AppIconPlan.tap(AppIcon.AURORA))
        assertEquals(AppIconChoice.NORSE, AppIconPlan.tap(AppIcon.NORSE))
        assertEquals(AppIconChoice.VARDR, AppIconPlan.tap(AppIcon.VARDR))
        val switched = listOf(
            Triple(true, AppTheme.AURORA, AppIconChoice.MATCH), Triple(true, AppTheme.NORSE, AppIconChoice.MATCH),
            Triple(true, AppTheme.VARDR, AppIconChoice.MATCH),
            Triple(false, AppTheme.AURORA, AppIconChoice.AURORA), Triple(false, AppTheme.NORSE, AppIconChoice.NORSE),
            Triple(false, AppTheme.VARDR, AppIconChoice.VARDR),
        )
        for ((on, theme, choice) in switched) assertEquals("Match theme $on on $theme", choice, AppIconPlan.matchSwitched(on, theme))
    }

    /** Design 4 (Android): the launcher entries that are on, read from the three aliases' enabled settings, in AppIcon
     *  order. DEFAULT is the manifest's (Aurora on, the other two off), ENABLED is on, every DISABLED state is off; two
     *  on (the switcher enables before it disables) reads both, none on reads none. */
    @Test
    fun the_enabled_icons_read_the_aliases() {
        val default = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val on = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val off = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val user = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        assertEquals(listOf(0, 1, 2, 3), listOf(default, on, off, user))
        val a = "com.vardrlabs.skipti.IconAurora"
        val n = "com.vardrlabs.skipti.IconNorse"
        val v = "com.vardrlabs.skipti.IconVardr"
        val aurora = AppIcon.AURORA
        val norse = AppIcon.NORSE
        val vardr = AppIcon.VARDR
        val rows = listOf(
            mapOf(a to default, n to default, v to default) to listOf(aurora),
            emptyMap<String, Int>() to listOf(aurora),
            mapOf(a to on, n to off, v to off) to listOf(aurora),
            mapOf(a to off, n to on, v to off) to listOf(norse),
            mapOf(a to off, n to off, v to on) to listOf(vardr),
            mapOf(a to off, n to on, v to default) to listOf(norse),
            mapOf(a to user, n to on, v to off) to listOf(norse),
            mapOf(a to off, n to on, v to on) to listOf(norse, vardr),
            mapOf(a to on, n to off, v to on) to listOf(aurora, vardr),
            mapOf(a to default, n to default, v to on) to listOf(aurora, vardr),
            mapOf(a to default, n to on, v to default) to listOf(aurora, norse),
            mapOf(a to on, n to on, v to on) to listOf(aurora, norse, vardr),
            mapOf(a to off, n to off, v to off) to emptyList(),
            mapOf(a to user, n to off, v to off) to emptyList(),
            mapOf(a to off, n to user, v to default) to emptyList(),
        )
        for ((states, icons) in rows) assertEquals("enabled $states", icons, AppIconPlan.enabled(states))
    }

    /** Task 2's review (1) and the whole-branch review's I1: the switcher decides on EVERY entry that is on, so the
     *  phone never ends with two launcher entries (below API 33 the writes go one by one, and an interrupted run can
     *  leave the target and another on) nor with none. It writes nothing only when the target alone is on. Every set of
     *  entries (none, one, each pair, all three) against every target, the rows written out by hand. */
    @Test
    fun the_switcher_writes_unless_the_target_alone_is_on() {
        val on = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val off = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val default = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        fun states(vararg icons: AppIcon) = AppIcon.entries.associate { it.alias to if (it in icons) on else off }
        val aurora = AppIcon.AURORA
        val norse = AppIcon.NORSE
        val vardr = AppIcon.VARDR
        // Each set of entries that are on, and the one target (if any) for which nothing is written.
        val rows = listOf(
            states() to null,
            states(aurora) to aurora,
            states(norse) to norse,
            states(vardr) to vardr,
            states(aurora, norse) to null,
            states(aurora, vardr) to null,
            states(norse, vardr) to null,
            states(aurora, norse, vardr) to null,
        )
        assertEquals("all 8 sets", 8, rows.map { it.first }.toSet().size)
        for ((set, quiet) in rows) {
            for (target in AppIcon.entries) {
                assertEquals("$target over $set", target != quiet, AppIconPlan.needsWrite(target, set))
            }
        }
        // I1's case by name: Aurora and Norse on, Aurora the target (the old rule read only the first on, Aurora).
        assertTrue("two on, the first the target: written", AppIconPlan.needsWrite(aurora, states(aurora, norse)))
        // Nothing written yet (every alias at DEFAULT, or no state read): the manifest's Aurora alone is on.
        val untouched = AppIcon.entries.associate { it.alias to default }
        assertFalse("untouched, Aurora: nothing", AppIconPlan.needsWrite(aurora, untouched))
        assertFalse("no state read, Aurora: nothing", AppIconPlan.needsWrite(aurora, emptyMap()))
        assertTrue("untouched, Norse: written", AppIconPlan.needsWrite(norse, untouched))
        assertTrue("untouched, Vardr Labs: written", AppIconPlan.needsWrite(vardr, untouched))
    }

    /** Design 4 (Android): what the switcher writes, the target enabled FIRST (never a moment with no launcher entry),
     *  then the other two disabled, in AppIcon order. */
    @Test
    fun the_settings_enable_the_target_first() {
        val a = "com.vardrlabs.skipti.IconAurora"
        val n = "com.vardrlabs.skipti.IconNorse"
        val v = "com.vardrlabs.skipti.IconVardr"
        assertEquals(listOf(a to true, n to false, v to false), AppIconPlan.settings(AppIcon.AURORA))
        assertEquals(listOf(n to true, a to false, v to false), AppIconPlan.settings(AppIcon.NORSE))
        assertEquals(listOf(v to true, a to false, n to false), AppIconPlan.settings(AppIcon.VARDR))
    }

    /** Design 5's copy, verbatim, the same on both phones. Decision 33 (the look's F1: on Launcher3 the home-screen
     *  icon survives the switch, redrawn as the new icon) removed Android's second caption sentence, so AppIconCopy
     *  holds exactly iOS's three strings and no Android-only one. */
    @Test
    fun the_icon_copy() {
        assertEquals("App icon", AppIconCopy.TITLE)
        assertEquals("Match theme", AppIconCopy.MATCH)
        assertEquals("Your icon changes when you leave this page.", AppIconCopy.CAPTION)
        val strings = AppIconCopy::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.name }.toSet()
        assertEquals("no Android-only sentence", setOf("TITLE", "MATCH", "CAPTION"), strings)
        assertEquals("Aurora icon", AppIconCopy.spoken(AppIcon.AURORA))
        assertEquals("Norse icon", AppIconCopy.spoken(AppIcon.NORSE))
        assertEquals("Vardr Labs icon", AppIconCopy.spoken(AppIcon.VARDR))
    }

    /** Twins: iOS's Appearance.swift holds the same choice words, icons, storage key and copy, and exactly the two
     *  alternate icon names (a missing, extra or misspelled name fails). */
    @Test
    fun the_icon_names_match_ios() {
        val swift = java.io.File("../../ios/Skipti/App/Appearance.swift").readText()
        fun block(from: String): String {
            assertTrue("Appearance.swift has `$from`", from in swift)
            return swift.substringAfter(from).substringBefore("\n}\n")
        }
        fun cases(from: String) = Regex("""(?m)^\s*case (\w+)$""").findAll(block(from)).map { it.groupValues[1] }.toList()
        assertEquals(AppIconChoice.entries.map { it.stored }, cases("enum AppIconChoice: String, CaseIterable {"))
        assertEquals(AppIcon.entries.map { it.stored }, cases("enum AppIcon: String, CaseIterable {"))
        val choice = block("enum AppIconChoice: String, CaseIterable {")
        assertEquals(
            "appearance." + AppIconChoice.STORAGE_KEY,
            Regex("""static let storageKey = "([^"]*)"""").find(choice)?.groupValues?.get(1),
        )
        // The alternate names, pinned per Android icon: Aurora is iOS's primary icon.
        val names = mapOf(AppIcon.AURORA to null, AppIcon.NORSE to "AppIcon-Norse", AppIcon.VARDR to "AppIcon-Vardr")
        assertEquals("one name per icon", AppIcon.entries.toSet(), names.keys)
        val alternate = block("enum AppIcon: String, CaseIterable {").substringAfter("var alternateName: String? {").substringBefore("\n    }\n")
        val iosNames = Regex("""case \.(\w+): return (nil|"([^"]*)")""").findAll(alternate)
            .associate { it.groupValues[1] to (if (it.groupValues[2] == "nil") null else it.groupValues[3]) }
        assertEquals(names.mapKeys { it.key.stored }, iosNames)
        val everyName = Regex(""""(AppIcon-[^"]*)"""").findAll(swift).map { it.groupValues[1] }.toSet()
        assertEquals("no other alternate name in Appearance.swift", names.values.filterNotNull().toSet(), everyName)
        // The copy: iOS's static strings and spoken names are Android's, one for one.
        // D-234: code lines only (iOS's doc comment on `choice` quotes the `case .x: return "..."` shape).
        val copy = block("enum AppIconCopy {").lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val iosCopy = Regex("""static let (\w+) = "([^"]*)"""").findAll(copy).associate { it.groupValues[1].uppercase() to it.groupValues[2] }
        assertEquals(mapOf("TITLE" to AppIconCopy.TITLE, "MATCH" to AppIconCopy.MATCH, "CAPTION" to AppIconCopy.CAPTION), iosCopy)
        val iosSpoken = Regex("""case \.(\w+): return "([^"]*)"""").findAll(copy).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(AppIcon.entries.associate { it.stored to AppIconCopy.spoken(it) }, iosSpoken)
    }

    /** The look's F3 (the uiautomator dump read the CHECKED icon row `clickable=false`, yet a tap on it turns Match theme
     *  off): Compose reports a selected node whose role is RadioButton or Tab as not clickable and gives TalkBack no
     *  click on it (AndroidComposeViewAccessibilityDelegateCompat in Compose UI 1.12: `info.isClickable =
     *  !isRadioButtonOrTab || !isSelected`; the rule is copied below). So a chosen row whose tap still acts drops the
     *  RadioButton role (it keeps its selection, so TalkBack says "Selected") and keeps the RadioButton class through
     *  [ListRows.className]. Every App icon row, in every choice and theme: TalkBack can click it exactly when its tap
     *  changes what is stored. */
    @Test
    fun a_chosen_row_that_still_acts_stays_clickable() {
        fun clickable(role: androidx.compose.ui.semantics.Role?, selected: Boolean): Boolean {
            val radioOrTab = role == androidx.compose.ui.semantics.Role.Tab || role == androidx.compose.ui.semantics.Role.RadioButton
            return !radioOrTab || !selected
        }
        // What a tap stores differs from what is stored: the rows, written out (Match theme on: every row acts).
        val acts = listOf(
            Triple(AppIconChoice.MATCH, AppIcon.AURORA, true), Triple(AppIconChoice.MATCH, AppIcon.NORSE, true),
            Triple(AppIconChoice.MATCH, AppIcon.VARDR, true),
            Triple(AppIconChoice.AURORA, AppIcon.AURORA, false), Triple(AppIconChoice.AURORA, AppIcon.NORSE, true),
            Triple(AppIconChoice.AURORA, AppIcon.VARDR, true),
            Triple(AppIconChoice.NORSE, AppIcon.AURORA, true), Triple(AppIconChoice.NORSE, AppIcon.NORSE, false),
            Triple(AppIconChoice.NORSE, AppIcon.VARDR, true),
            Triple(AppIconChoice.VARDR, AppIcon.AURORA, true), Triple(AppIconChoice.VARDR, AppIcon.NORSE, true),
            Triple(AppIconChoice.VARDR, AppIcon.VARDR, false),
        )
        for ((choice, icon, want) in acts) assertEquals("a tap on $icon with $choice acts", want, AppIconPlan.tapActs(icon, choice))
        for (theme in AppTheme.entries) {
            for ((choice, icon, want) in acts) {
                val chosen = AppIconPlan.checked(choice, theme) == icon
                val trailing = RowTrailing.Check(chosen, acts = AppIconPlan.tapActs(icon, choice))
                assertEquals("$icon row, $choice on $theme: clickable to TalkBack", want, clickable(ListRows.checkRole(trailing), chosen))
                assertEquals(
                    "$icon row, $choice on $theme: a radio button to TalkBack either way",
                    true,
                    ListRows.checkRole(trailing) == androidx.compose.ui.semantics.Role.RadioButton ||
                        ListRows.className(trailing) == "android.widget.RadioButton",
                )
            }
        }
        // Every other choice row (a choice page's option) keeps the RadioButton role, chosen or not.
        for (chosen in listOf(true, false)) {
            assertEquals(androidx.compose.ui.semantics.Role.RadioButton, ListRows.checkRole(RowTrailing.Check(chosen)))
            assertEquals(null, ListRows.className(RowTrailing.Check(chosen)))
        }
        // ListRow takes the role from the decision.
        val row = java.io.File("src/main/java/com/vardrlabs/skipti/ListRows.kt").readText().substringAfter("fun ListRow(").substringBefore("\n}\n")
        val check = row.substringAfter("is RowTrailing.Check -> Modifier.selectable(").substringBefore("\n        )")
        assertTrue("the check row's role is the decision's: $check", "role = ListRows.checkRole(trailing)," in check)
        assertFalse("no fixed role on the check row", "role = Role.RadioButton" in check)
    }

    /** Design 5 (.drafts/d216/art.py section 57): the preview tile's two keys, appended after D-226's last, in art.out's
     *  order (ThemeArtTest's parity test holds them equal to iOS's). */
    @Test
    fun the_icon_preview_metrics() {
        // D-243 (T7) appends Norse's card-edge glow (3 keys) after these.
        assertEquals(listOf("iconPreview" to 36.0, "iconPreviewRadius" to 0.225), ThemeArt.METRICS.toList().dropLast(3).takeLast(2))
        assertTrue("the preview fits the touch row", ThemeArt.m("iconPreview") <= ThemeArt.m("touchTarget"))
        assertTrue("the 144 px preview covers 3x", 144f >= 3 * ThemeArt.m("iconPreview"))
    }

    /** Every Kotlin file of the app, by name. */
    private fun mainFiles(): Map<String, String> =
        java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }

    /** The switcher's body in Appearance.kt. */
    private fun switcher(): String {
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/Appearance.kt").readText()
        assertTrue("Appearance.kt has AppIconSwitcher", "object AppIconSwitcher {" in src)
        return src.substringAfter("object AppIconSwitcher {").substringBefore("\n}\n")
    }

    /** Each call of [name] in [src] (not a longer name ending in it), as its argument text between the parentheses. */
    private fun argumentsOf(src: String, name: String): List<String> =
        Regex("""(?<![A-Za-z])$name\(""").findAll(src).map { match ->
            var depth = 1
            var i = match.range.last + 1
            while (depth > 0) {
                when (src[i]) { '(' -> depth++; ')' -> depth-- }
                i++
            }
            src.substring(match.range.last + 1, i - 1)
        }.toList()

    /** D-216 (Design 4): the launcher entries change in ONE place, AppIconSwitcher, every write with DONT_KILL_APP; the
     *  switcher runs from ONE place, the Appearance route's onDispose, skipped while the activity is only being made
     *  again (a rotation, a mode change); never at launch, never in MainActivity or a lifecycle callback. The twin of
     *  iOS's testTheIconChangesOnlyWhenAppearanceCloses. */
    @Test
    fun the_icon_changes_only_when_appearance_closes() {
        val files = mainFiles()
        val body = switcher()
        for ((name, text) in files) {
            val outside = if (name == "Appearance.kt") text.replace(body, "") else text
            assertFalse("$name writes a component's enabled setting outside AppIconSwitcher", "setComponentEnabledSetting" in outside)
        }
        val oneByOne = argumentsOf(body, "setComponentEnabledSetting")
        val atOnce = argumentsOf(body, "ComponentEnabledSetting")
        assertEquals("one write one by one, one setting for API 33's call", listOf(1, 1), listOf(oneByOne.size, atOnce.size))
        for (args in oneByOne + atOnce) {
            assertTrue("every write keeps the app running: $args", args.trim().endsWith("PackageManager.DONT_KILL_APP"))
        }
        val calls = files.flatMap { (name, text) -> Regex("""AppIconSwitcher\.applyIfNeeded\(""").findAll(text).map { name }.toList() }
        assertEquals("one call, in the Appearance route", listOf("AccountHubScreen.kt"), calls)
        assertTrue(
            "the call is the Appearance route's onDispose, skipped on a configuration change",
            Regex(
                """if \(section == SettingsSection\.APPEARANCE\) \{\s*DisposableEffect\([^)]*\) \{\s*onDispose \{\s*""" +
                    """if \([^)]*isChangingConfigurations[^)]*\) AppIconSwitcher\.applyIfNeeded\(""",
            ).containsMatchIn(files.getValue("AccountHubScreen.kt")),
        )
        val activity = files.getValue("AppRoot.kt").substringAfter("class MainActivity : ComponentActivity() {").substringBefore("\n}\n")
        assertFalse("MainActivity never switches the icon", "AppIconSwitcher" in activity)
        // D-244 (ADR-0043; ADR-0041's App icon section amended), amended ON PURPOSE to exactly two named call sites: the
        // Appearance route's leave (`applyIfNeeded`, above) and the front moment of a change that arrived from the account
        // (`applyArrived`, once, in SettingsSync's `changeIfPending`), which acts only on the arrival's pending mark, with
        // SKIPTI in front and unlocked (SettingsSyncTest pins the decision and where the moment is called from). MainActivity
        // calls only SettingsSync's front hook, never the switcher. The twin of iOS's amended guard.
        val arrived = files.flatMap { (name, text) -> Regex("""AppIconSwitcher\.applyArrived\(""").findAll(text).map { name }.toList() }
        assertEquals("the arrival's one call", listOf("SettingsSync.kt"), arrived)
        assertEquals(
            "the Appearance route and the front moment",
            listOf("AccountHubScreen.kt", "SettingsSync.kt"),
            files.filterValues { "AppIconSwitcher." in it }.keys.sorted(),
        )
        val sync = files.getValue("SettingsSync.kt")
        assertEquals("one call in SettingsSync", 1, sync.split("AppIconSwitcher.").size - 1)
        val pending = sync.substringAfter("private fun changeIfPending(app: Context, store: LedgerStore) {").substringBefore("\n    }\n")
        assertTrue(pending, "AppIconSwitcher.applyArrived(app, choice, theme)" in pending)
        assertTrue("through the pending decision", "frontMoment(store, prefsOf(app), locked = AppLock.locked, active = !AppLock.away)" in pending)
        val arrival = switcher().substringAfter("fun applyArrived(context: Context, choice: AppIconChoice, theme: AppTheme): Boolean {").substringBefore("\n    }\n")
        assertTrue("never in the background", "val active = !AppLock.away" in arrival)
        assertTrue("the same switch, in front only", "if (active) switchTo(context, AppIconPlan.target(choice, theme))" in arrival)
        assertTrue("the leave's switch is the same", "fun applyIfNeeded(context: Context) = switchTo(context, AppIconPlan.target(Appearance.iconChoice, Appearance.theme))" in switcher())
        assertEquals("MainActivity's one SettingsSync call, the front hook", 1, activity.split("SettingsSync.").size - 1)
        assertTrue("if (returned) SettingsSync.frontMoment(this)" in activity)
    }

    /** D-216 (Design 4): the switcher reads the launcher, writes only when the plan says so, and writes the plan's
     *  settings in their order (the target enabled first): in one call on API 33+, below it one by one. */
    @Test
    fun the_switch_enables_before_it_disables() {
        val body = switcher()
        assertTrue("reads every alias", "val states = AppIcon.entries.associate { it.alias to pm.getComponentEnabledSetting(" in body)
        assertTrue("writes only when the plan says so, from every entry that is on", "if (!AppIconPlan.needsWrite(target, states)) return" in body)
        val settings = body.indexOf("val settings = AppIconPlan.settings(target)")
        val gate = body.indexOf("if (Build.VERSION.SDK_INT >= 33) {")
        val plural = body.indexOf("setComponentEnabledSettings(")
        val orElse = body.indexOf("} else {", plural.coerceAtLeast(0))
        val single = Regex("""(?<![A-Za-z])setComponentEnabledSetting\(""").find(body)?.range?.first ?: -1
        assertTrue(
            "the plan, the API 33 gate, the one call, else one by one: $settings $gate $plural $orElse $single",
            settings in 0 until gate && gate < plural && plural < orElse && orElse < single,
        )
        assertEquals("one call on API 33+", 1, Regex("""setComponentEnabledSettings\(""").findAll(body).count())
        assertTrue("API 33's call takes the plan in its order", "settings.map { (alias, on) ->" in body.substring(plural, orElse))
        assertTrue("below 33, one by one in the plan's order", "for ((alias, on) in settings)" in body.substring(orElse))
        for (reorder in listOf("reversed", "sorted", "shuffled", "asReversed")) assertFalse("no $reorder", reorder in body)
    }

    /** D-216 (Design 5): the App icon section sits after Light or dark and before Text size; one list card of the
     *  Match theme switch and the three icon rows (a radio group), the caption under it; the preview tile is the icon's
     *  own shape at iconPreview and iconPreviewRadius in every theme, never a theme tile. The twin of iOS's
     *  testThePickerSitsBetweenModeAndTextSize. */
    @Test
    fun the_picker_sits_between_mode_and_text_size() {
        val src = hub()
        val mode = item("APPEARANCE_MODE", "TEXT_SIZE")
        assertEquals("one call", 1, Regex("""(?<!fun )AppIconSection\(dark\)""").findAll(src).count())
        val control = mode.indexOf("ModeControl(dark)")
        val icon = mode.indexOf("AppIconSection(dark)")
        assertTrue("after the mode control, in Light or dark's item: $control $icon", control in 0 until icon)
        assertTrue("before Text size", src.indexOf("AppIconSection(dark)") < src.indexOf("SettingItem.TEXT_SIZE ->"))
        val section = fn("AppIconSection")
        // D-234 (A2, decision 80): one card; its header row says "App icon", the current choice and the chevron; the four
        // rows open under it in the same card, a hairline before each; the caption only while open.
        val card = section.indexOf("ListCard(dark) {")
        val header = section.indexOf("RowTrailing.Disclosure(AppIconCopy.choice(choice), expanded)")
        val rows = section.indexOf("Column(Modifier.selectableGroup()) {")
        val caption = section.indexOf("CardCaption(AppIconCopy.CAPTION, dark)")
        assertTrue("card, header, rows, caption: $card $header $rows $caption", card in 0 until header && header < rows && rows < caption)
        assertFalse("no label above the card: the header row says it", "SectionLabel(" in section)
        assertTrue("closed each time the page opens, never stored", "var expanded by remember { mutableStateOf(false) }" in section)
        assertTrue("the header toggles it", "{ expanded = !expanded }" in section)
        assertEquals("the rows and the caption open and close", 2, section.split("AnimatedVisibility(").size - 1)
        assertEquals("one caption", 1, section.split("CardCaption(").size - 1)
        assertEquals("one card", 1, section.split("ListCard(").size - 1)
        assertEquals("one switch row", 1, section.split("RowTrailing.Toggle(").size - 1)
        assertEquals("one check row, for each icon", 1, section.split("RowTrailing.Check(").size - 1)
        assertEquals("a hairline before each open row", 2, section.split("Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hairline(dark)))").size - 1)
        assertTrue(
            "the check row says whether its tap still acts (the look's F3)",
            "RowTrailing.Check(AppIconPlan.checked(choice, theme) == icon, acts = AppIconPlan.tapActs(icon, choice))" in section,
        )
        for (piece in listOf(
            "AppIcon.entries.forEach", "AppIconPlan.checked(", "AppIconPlan.tap(", "AppIconPlan.matchSwitched(",
            "AppIconCopy.spoken(", "AppIconCopy.MATCH", "Appearance.setIconChoice(context, ",
            "val motion = rememberMotionState()", "val still = motion.reduce",
            "val ms = AppIconPlan.expandMillis(moves = !still)",
        )) {
            assertTrue("the section has $piece", piece in section)
        }
        val preview = fn("IconPreview")
        assertTrue("the icon's own image", "bitmap = ImageBitmap.imageResource(image)" in preview)
        // The whole-branch review's M5: the 144 px image drawn at 36 is filtered high, the twin of iOS's
        // `.interpolation(.high)` (Compose's default is low).
        assertTrue("filtered high", "filterQuality = FilterQuality.High" in preview)
        assertFalse("no painter (it has no filter quality)", "painterResource(" in preview)
        assertTrue(
            "the icon's own corner",
            "RoundedCornerShape((ThemeArt.m(\"iconPreview\") * ThemeArt.m(\"iconPreviewRadius\")).dp)" in preview,
        )
        assertTrue("at iconPreview", ".size(ThemeArt.m(\"iconPreview\").dp)" in preview)
        assertFalse("never a theme tile", "TileShape" in preview || "tileShape(" in preview)
        assertTrue("decorative", "contentDescription = null" in preview)
    }

    /** D-234 (A2, decision 80): the twin of iOS's testTheAppIconHeaderOpensAndCloses: the header's value is the choice,
     *  the chevron turns 90 degrees while open, TalkBack reads "App icon, <choice>" and the state; the rows take the
     *  switch's own travel when art may move and snap (0) when it may not. The words are iOS's. */
    @Test
    fun the_app_icon_header_opens_and_closes() {
        assertEquals(listOf("Match theme", "Aurora", "Norse", "Vardr Labs"), AppIconChoice.entries.map { AppIconCopy.choice(it) })
        assertEquals(200, AppIconPlan.expandMillis(moves = true))
        assertEquals("the switch's own travel, rounded as the chevron's tween is (M3)", (ThemeArt.m("switchTravel") * 1000).roundToInt(), AppIconPlan.expandMillis(moves = true))
        assertEquals("the motion door shut: a snap", 0, AppIconPlan.expandMillis(moves = false))
        assertEquals(0f, RowDisclosure.chevronDegrees(false))
        assertEquals(90f, RowDisclosure.chevronDegrees(true))
        assertEquals("collapsed", RowDisclosure.state(false))
        assertEquals("expanded", RowDisclosure.state(true))
        assertEquals("App icon, Match theme", ListRows.spoken("App icon", emptyList(), RowTrailing.Disclosure("Match theme", false)))
        val rows = java.io.File("src/main/java/com/vardrlabs/skipti/ListRows.kt").readText()
        for (needle in listOf(
            "stateDescription = RowDisclosure.state(d.expanded)",
            "if (d.expanded) collapse { onClick(); true } else expand { onClick(); true }",
            "if (still) snap() else tween((ThemeArt.m(\"switchTravel\") * 1000).roundToInt(), easing = EaseInOut)",
            "Box(Modifier.rotate(turn)) { RowChevron(Tok.textDim(dark)) }",
        )) {
            assertTrue("ListRows.kt: $needle", needle in rows)
        }
        val swiftRows = java.io.File("../../ios/Skipti/App/ListRows.swift").readText()
        assertTrue("static let expanded = \"${RowDisclosure.EXPANDED}\"" in swiftRows)
        assertTrue("static let collapsed = \"${RowDisclosure.COLLAPSED}\"" in swiftRows)
        assertTrue("open ? 90 : 0" in swiftRows)
        val swiftAppearance = java.io.File("../../ios/Skipti/App/Appearance.swift").readText()
        assertTrue("case .match: return AppIconCopy.match" in swiftAppearance)
    }

    /** D-216: `--notification-proof` (a look's tool: one notification with fixed text, to see the status bar glyph)
     *  runs only in a debuggable build: it is reached only through ProofArgs.proof, which honours nothing in a release
     *  build, and nothing else reads it. */
    @Test
    fun the_notification_proof_is_debug_only() {
        assertEquals(null, ProofArgs.proof("--notification-proof", debuggable = false))
        assertEquals("--notification-proof", ProofArgs.proof("--notification-proof", debuggable = true))
        val hits = mainFiles().flatMap { (name, text) -> Regex("""notification-proof""").findAll(text).map { name }.toList() }
        assertEquals("read in one place", listOf("AppRoot.kt"), hits)
        val root = mainFiles().getValue("AppRoot.kt")
        val arms = root.substringAfter("    when (headless) {\n").substringBefore("\n    }\n")
        assertTrue("an arm of the proofs' when", "\"notification-proof\" -> {\n            NotificationProof()\n            return\n        }" in arms)
        assertTrue("the proofs come from ProofArgs.proof", "verificationArg = proofArg()," in root)
        assertTrue(
            "ProofArgs.proof in a debuggable build only",
            "private fun proofArg(): String? = ProofArgs.proof(intent?.getStringExtra(\"args\"), debuggable())" in root &&
                "if (!debuggable || tokens(trimmed).first() == \"--emulators\") return null" in root,
        )
        assertTrue("headless is the proof's first word", "val headless: String? = rememberOnce { args.firstOrNull()?.removePrefix(\"--\") }" in root)
    }
}

/** D-232: a SharedPreferences held in a map, for the launch migration's JVM test (this project has no Robolectric;
 *  SharedPreferences is an interface, so a plain class can stand in). Only what the migration uses needs to work. */
private class FakePrefs : android.content.SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    /** How the edits were written: `commit()` is synchronous, `apply()` is not (D-232's settled mark needs the first). */
    var commits = 0
    var applies = 0

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = (map[key] as String?) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as Int?) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as Long?) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as Float?) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as Boolean?) ?: defValue
    override fun contains(key: String?): Boolean = key in map
    override fun edit(): android.content.SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        private fun put(key: String?, value: Any?): android.content.SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            removed += key!!
            return this
        }
        override fun clear(): android.content.SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            commits++
            write()
            return true
        }
        override fun apply() {
            applies++
            write()
        }
        private fun write() {
            if (clearAll) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
