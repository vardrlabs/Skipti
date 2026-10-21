package com.vardrlabs.skipti

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * D-213 (E2, ADR-0041 "Theme art"): the twin of iOS ThemeArtTests, plus the
 * parity check: iOS's ThemeArt.swift is read and every rune, colour and size
 * compared with ThemeArt.kt (the file is a declared test input).
 */
class ThemeArtTest {
    @After
    fun reset() = Appearance.setForTest()

    private val swift = java.io.File("../../ios/Skipti/App/ThemeArt.swift").readText()

    @Test
    fun the_glyph_table_is_the_specs() {
        fun cp(s: String) = s.codePoints().toArray().toList()
        assertEquals(listOf(0x16A0), cp(Runes.BALANCE))
        assertEquals(listOf(0x16B7), cp(Runes.EXPENSES))
        assertEquals(listOf(0x16D7), cp(Runes.GROUPS))
        assertEquals(listOf(0x16CF), cp(Runes.SETTLE))
        assertEquals(listOf(0x16EB), cp(Runes.SEPARATOR))
        assertEquals(listOf(0x16DF), cp(Runes.SECTION_MARK))
        assertEquals(listOf(0x16DC), cp(Runes.ORNAMENT))
        assertEquals(
            listOf(
                0x16A0, 0x16A2, 0x16A6, 0x16A8, 0x16B1, 0x16B2, 0x16B7, 0x16B9, 0x16BA, 0x16BE, 0x16C1, 0x16C3,
                0x16C7, 0x16C8, 0x16C9, 0x16CA, 0x16CF, 0x16D2, 0x16D6, 0x16D7, 0x16DA, 0x16DC, 0x16DE, 0x16DF,
            ),
            Runes.FUTHARK.flatMap(::cp),
        )
        assertEquals(25, Runes.ALL.size)
        assertEquals(listOf(Runes.BALANCE, Runes.EXPENSES, Runes.GROUPS, Runes.SETTLE), HomeTab.entries.map { it.rune })
    }

    /** Parity: every named rune and the Futhark in ThemeArt.swift equal Kotlin's. */
    @Test
    fun the_runes_match_ios() {
        fun scalar(name: String): Int {
            val m = Regex("""static let $name = "\\u\{([0-9A-F]+)\}"""").find(swift)
            assertTrue("iOS rune $name parsed", m != null)
            return m!!.groupValues[1].toInt(16)
        }
        assertEquals(Runes.BALANCE.codePointAt(0), scalar("balance"))
        assertEquals(Runes.EXPENSES.codePointAt(0), scalar("expenses"))
        assertEquals(Runes.GROUPS.codePointAt(0), scalar("groups"))
        assertEquals(Runes.SETTLE.codePointAt(0), scalar("settle"))
        assertEquals(Runes.SEPARATOR.codePointAt(0), scalar("separator"))
        assertEquals(Runes.SECTION_MARK.codePointAt(0), scalar("sectionMark"))
        assertEquals(Runes.ORNAMENT.codePointAt(0), scalar("ornament"))
        val block = swift.substringAfter("static let futhark = [").substringBefore("]")
        val ios = Regex("""\\u\{([0-9A-F]+)\}""").findAll(block).map { it.groupValues[1].toInt(16) }.toList()
        assertEquals(Runes.FUTHARK.map { it.codePointAt(0) }, ios)
    }

    /** Parity: the art's colours and sizes in ThemeArt.swift equal Kotlin's, key by key. */
    @Test
    fun the_art_values_match_ios() {
        val hexBlock = swift.substringAfter("static let hex: [String: String] = [").substringBefore("]")
        val iosHex = Regex(""""(\w+)": "(#[0-9A-F]{6})"""").findAll(hexBlock).associate { it.groupValues[1] to it.groupValues[2] }
        // D-221: 16 colours (runeUnlit, norseMeterGap) and 44 sizes (the ring, the meter, the dot, the seal, the tile);
        // D-222: 69 sizes (You's tiles and list, the theme cards and swatches, the mode control, the slider).
        // D-223: 78 sizes (the ledger grid and card, Lately's rows, the empty state's icon, the Groups card's spacing).
        // D-224: 17 colours (norseCheckFill) and 94 sizes (the done check, the toast and its place).
        // D-225: 110 sizes (the list row's line gap, the switch, the emoji grid, the placeholders, the lock, the cross).
        // D-226: 122 sizes (the Norse hero's frame and watermark, Aurora's glass; nine values scaled x 1.28).
        // D-216: 124 sizes (the App icon rows' preview tile: its side and corner ratio).
        // D-243 (T7): 127 sizes (Norse's card-edge glow: norseEdgeGlow, norseEdgeGlowSigma, norseEdgeGlowBand).
        assertEquals(17, iosHex.size)
        assertEquals(iosHex, ThemeArt.HEX)
        val numBlock = swift.substringAfter("static let metrics: [String: Double] = [").substringBefore("]")
        val iosNum = Regex(""""(\w+)": (-?[0-9.]+)""").findAll(numBlock).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
        assertEquals(127, iosNum.size)
        for ((k, v) in listOf("norseEdgeGlow" to 0.18, "norseEdgeGlowSigma" to 3.0, "norseEdgeGlowBand" to 0.5)) assertEquals(k, v, iosNum[k])
        assertEquals(iosNum, ThemeArt.METRICS)
    }

    /** D-221 parity: E4's two colours, read out of ThemeArt.swift's hex table, are Kotlin's, and both are art.py's. */
    @Test
    fun the_e4_hex_match_ios() {
        for ((key, value) in listOf("runeUnlit" to "#4A4740", "norseMeterGap" to "#1A1A1C")) {
            val ios = Regex(""""$key": "(#[0-9A-F]{6})"""").find(swift)?.groupValues?.get(1)
            assertEquals("iOS $key", value, ios)
            assertEquals("Kotlin $key", value, ThemeArt.HEX[key])
        }
    }

    /** D-221 (art.py sections 18, 19, 21, 22): the ring, the meter, the progress dot, the seal and the header tile. */
    @Test
    fun the_e4_art_values() {
        val expected = listOf(
            "ringBox" to 137f, "ringRadius" to 62f, "ringStroke" to 6f, "runeRingRadius" to 60f, "runeRingSize" to 14f,
            "meterBig" to 10f, "meterBigVardr" to 7f, "meterSmall" to 6f, "meterSmallVardr" to 4f, "meterSegment" to 11f,
            "meterGap" to 2f, "progressDot" to 7f, "progressDotGap" to 7f, "seal" to 44f, "groupTile" to 48f,
        )
        for ((key, value) in expected) assertEquals(key, value, ThemeArt.m(key), 0f)
        assertEquals("#4A4740", ThemeArt.HEX["runeUnlit"])
        assertEquals("#1A1A1C", ThemeArt.HEX["norseMeterGap"])
    }

    /** D-222 (art.py sections 24 to 28): You's icon tiles and list rhythm, the theme cards and swatches, the mode
     *  control and the text-size slider; the twin of iOS's testS1ArtValues. */
    @Test
    fun the_s1_art_values() {
        val expected = listOf(
            "settingsTile" to 33f, "settingsGlyph" to 19f, "listPadH" to 15f, "listPadV" to 3f, "rowPadV" to 10f, "rowGap" to 13f,
            "themePad" to 8f, "themeGap" to 6f, "swatchHeight" to 59f, "swatchRadius" to 10f, "swatchRune" to 20f,
            "swatchRuneTracking" to 0.12f, "swatchGridPitch" to 8f, "swatchGridDot" to 0.9f, "swatchMarkInset" to 6f,
            "swatchMarkLength" to 10f, "modeTrack" to 40f, "modeInset" to 4f, "touchTarget" to 48f, "segmentShadowAlpha" to 0.15f,
            "segmentShadowRadius" to 2f, "segmentShadowY" to 1f, "sliderTrack" to 5f, "sliderThumb" to 20f, "sliderRing" to 2.5f,
        )
        assertEquals(25, expected.size)
        for ((key, value) in expected) assertEquals(key, value, ThemeArt.METRICS[key]?.toFloat() ?: -1f, 1e-6f)
    }

    /** D-223 (.drafts/d223/art.py sections 30 to 34): the ledger grid and card, Lately's rows, the empty state's icon
     *  and the Groups card's spacing; the twin of iOS's testHomeArtValues, retyped from art.out. */
    @Test
    fun the_home_art_values() {
        val expected = listOf(
            "ledgerTile" to 33f, "ledgerTileGap" to 9f, "ledgerCardGap" to 6f, "ledgerGridGap" to 10f, "latelyAvatar" to 33f,
            "latelyAvatarGap" to 12f, "latelyRowGap" to 11f, "emptyIcon" to 44f, "groupsCardSpacing" to 12f,
        )
        assertEquals(9, expected.size)
        for ((key, value) in expected) assertEquals(key, value, ThemeArt.METRICS[key]?.toFloat() ?: -1f, 0f)
        // D-224 appends E3's 16 after these nine, D-225 S2's 16 after those, D-226 A's 12 after those, D-216's 2 after those, D-243's 3 after those.
        assertEquals("appended after D-222's last, in art.out's order", expected.map { it.first }, ThemeArt.METRICS.keys.toList().dropLast(49).takeLast(9))
    }

    /** D-224 (.drafts/d224/art.py sections 37 to 39): the done check, the toast and where it sits, Norse's check fill and
     *  the tick's three points; the twin of iOS's testE3ArtValues, retyped from art.out. */
    @Test
    fun the_e3_art_values() {
        val expected = listOf(
            "doneCheck" to 104f, "doneCircleRadius" to 48f, "doneCircleStroke" to 3f, "doneTickStroke" to 6f,
            "toastRadius" to 18f, "toastPadV" to 12f, "toastPadH" to 15f, "toastGap" to 10f, "toastDot" to 26f,
            "toastGlyph" to 15f, "toastCheckStroke" to 3.5f, "toastInset" to 16f, "toastClearOfBar" to 104f,
            "toastShadowAlpha" to 0.14f, "toastShadowRadius" to 9f, "toastShadowY" to 6f,
        )
        assertEquals(16, expected.size)
        for ((key, value) in expected) assertEquals(key, value, ThemeArt.METRICS[key]?.toFloat() ?: -1f, 1e-6f)
        // D-225 appends S2's 16 after these, D-226 A's 12 after those, D-216's 2 after those, D-243's 3 after those.
        assertEquals("appended after D-223's last, in art.out's order", expected.map { it.first }, ThemeArt.METRICS.keys.toList().dropLast(33).takeLast(16))
        assertEquals("#18120A", ThemeArt.HEX["norseCheckFill"])
        assertEquals(listOf(32.0 to 54.0, 46.0 to 67.0, 73.0 to 39.0), DoneCheck.TICK)
    }

    /** D-225 (.drafts/d225/art.py sections 41 to 45): the list row's line gap, the drawn switch, the emoji grid, the
     *  picture's placeholder ratios, the lock screen's glyph and the clear cross; the twin of iOS's testS2ArtValues,
     *  retyped from art.out. */
    @Test
    fun the_s2_art_values() {
        val expected = listOf(
            "rowLineGap" to 2f, "switchWidth" to 51f, "switchHeight" to 31f, "switchThumb" to 27f, "switchInset" to 2f,
            "switchBorder" to 1.5f, "switchTravel" to 0.2f, "emojiColumns" to 6f, "emojiGap" to 8f, "emojiGlyph" to 24f,
            "emojiBorder" to 2f, "emojiRadius" to 8f, "avatarGlyph" to 0.5f, "avatarEmoji" to 0.55f, "lockGlyph" to 40f,
            "clearGlyph" to 18f,
        )
        assertEquals(16, expected.size)
        for ((key, value) in expected) assertEquals(key, value, ThemeArt.METRICS[key]?.toFloat() ?: -1f, 1e-6f)
        // D-226 appends A's 12 after these, D-216 its 2 after those, D-243 its 3 after those.
        assertEquals("appended after D-224's last, in art.out's order", expected.map { it.first }, ThemeArt.METRICS.keys.toList().dropLast(17).takeLast(16))
        // The thumb fits the track with the inset on each side (art.py 42: 31 = 27 + 2 x 2).
        assertEquals(ThemeArt.m("switchHeight"), ThemeArt.m("switchThumb") + 2 * ThemeArt.m("switchInset"), 0f)
    }

    /** D-226 (A, .drafts/d226/art.py sections 46, 51 and 53; the Global Constraints): the nine art values scaled x 1.28
     *  and the twelve new keys, appended after D-225's in art.out's order; the twin of iOS's testArtScaleValues,
     *  retyped from art.out. */
    @Test
    fun the_art_scale_values() {
        val changed = listOf(
            "chamfer" to 10f, "frameInset" to 4f, "frameLength" to 12f, "gridPitch" to 18f, "gridDot" to 0.77f,
            "diamondRadius" to 8f, "tileRadius" to 3f, "ornamentRune" to 17f, "pillChamfer" to 5f,
        )
        val added = listOf(
            "heroFrameInset" to 6f, "heroWatermark" to 118f, "heroWatermarkTrailing" to 13f, "heroWatermarkTop" to -8f,
            "glassFilm" to 0.78f, "glassFilmNoBlur" to 0.82f, "glassRim" to 0.35f, "glassRimLight" to 0.35f, "glassRimDark" to 0.35f,
            "glassShadowSigma" to 12f, "glassShadowY" to 4f, "glassShadowAlpha" to 0.06f,
        )
        assertEquals(9, changed.size)
        assertEquals(12, added.size)
        for ((key, value) in changed + added) assertEquals(key, value, ThemeArt.METRICS[key]?.toFloat() ?: -1f, 1e-6f)
        // D-216 appends its 2 after these, D-243 its 3 after those.
        assertEquals("appended after D-225's last, in art.out's order", added.map { it.first }, ThemeArt.METRICS.keys.toList().dropLast(5).takeLast(12))
        // The panel rule's two numbers and the ornament's row follow the chamfer and the rune (art.py 46).
        assertEquals(50f, ThemeArt.PANEL_TALLER_THAN, 0f)
        assertEquals(8f, ThemeArt.PANEL_SMALL_CHAMFER, 0f)
        assertEquals(20, TitleOrnaments.ROW_HEIGHT)
        assertEquals("the gap under the title stays", 8, TitleOrnaments.GAP_UNDER_TITLE)
        assertEquals(0.42f, TitleOrnaments.RULE_FRACTION, 0f)
    }

    /** D-226 (L35, Q18 a): the Norse card's edge is its four straight sides, each 0.5 in (the 1 dp line inside the
     *  shape), ending on the diagonals; the twin of iOS's testNotchEdges. Goldens: .drafts/d226/art.py section 52.
     *  Negatives: exactly four segments (never the eight sides of `notched`), and none diagonal. */
    @Test
    fun notch_edges() {
        fun flat(s: List<Pair<Offset, Offset>>) = s.map { listOf(it.first.x, it.first.y, it.second.x, it.second.y) }
        assertEquals(
            listOf(listOf(9.5f, 0.5f, 90.5f, 0.5f), listOf(99.5f, 9.5f, 99.5f, 50.5f), listOf(90.5f, 59.5f, 9.5f, 59.5f), listOf(0.5f, 50.5f, 0.5f, 9.5f)),
            flat(ArtGeometry.notchEdges(100f, 60f, ThemeArt.m("chamfer"))),
        )
        assertEquals(
            "clamped to 5",
            listOf(listOf(4.5f, 0.5f, 5.5f, 0.5f), listOf(9.5f, 4.5f, 9.5f, 5.5f), listOf(5.5f, 9.5f, 4.5f, 9.5f), listOf(0.5f, 5.5f, 0.5f, 4.5f)),
            flat(ArtGeometry.notchEdges(10f, 10f, ThemeArt.m("chamfer"))),
        )
        for ((w, h) in listOf(100f to 60f, 240f to 88f)) {
            val s = ArtGeometry.notchEdges(w, h, ThemeArt.m("chamfer"))
            assertEquals("four straight sides, not the eight of notched", 4, s.size)
            for ((a, b) in s) assertTrue("a segment is horizontal or vertical: $a $b", a.x == b.x || a.y == b.y)
        }
    }

    /** D-226 (L31): CSS angles as unit points; the twin of iOS's testCssGradient. Goldens: .drafts/d226/art.py section
     *  49 (1e-4; the bolt's 150 on 72 x 64 reproduces C1's values to 1e-3). Negative: 160 on a square is not the
     *  corner-to-corner pair. */
    @Test
    fun css_gradient() {
        fun check(deg: Double, w: Double, h: Double, want: List<Float>, tol: Float) {
            val (s, e) = CssGradient.unitPoints(deg, w, h)
            listOf(s.x, s.y, e.x, e.y).zip(want).forEach { (got, exp) -> assertEquals("$deg on $w x $h", exp, got, tol) }
        }
        check(160.0, 362.0, 200.0, listOf(0.3527f, -0.2324f, 0.6473f, 1.2324f), 1e-4f)
        check(160.0, 100.0, 100.0, listOf(0.2808f, -0.1022f, 0.7192f, 1.1022f), 1e-4f)
        check(150.0, 72.0, 64.0, listOf(ThemeArt.m("boltStartX"), ThemeArt.m("boltStartY"), ThemeArt.m("boltEndX"), ThemeArt.m("boltEndY")), 1e-3f)
        check(150.0, 72.0, 64.0, listOf(0.183f, -0.119f, 0.817f, 1.119f), 1e-3f)
        val square = CssGradient.unitPoints(160.0, 100.0, 100.0)
        assertNotEquals(Offset(0f, 0f) to Offset(1f, 1f), square)
    }

    /** D-226 (art.py section 46): the grid at the art's scaled pitch, 18; the twin of iOS's testGridAtTheScaledPitch. */
    @Test
    fun the_grid_at_the_scaled_pitch() {
        val pitch = ThemeArt.m("gridPitch")
        assertEquals(1078, ArtGeometry.gridDots(402f, 874f, pitch).size)
        assertNotEquals(1798, ArtGeometry.gridDots(402f, 874f, pitch).size)
        assertEquals(listOf(Offset(9f, 9f), Offset(27f, 9f), Offset(9f, 27f), Offset(27f, 27f)), ArtGeometry.gridDots(36f, 40f, pitch))
    }

    /** D-226 (L36, art.py sections 50 and 51): Compose's shadow radius for iOS's sigma 12 (Skia: sigma = 0.57735 r +
     *  0.5 px): 20.45 dp at density 2.625, 20.50 at 3. The negative: a sigma too small to blur is clamped to 0, never
     *  a negative radius. */
    @Test
    fun shadow_blur() {
        assertEquals(20.45f, ShadowBlur.radiusDp(ThemeArt.m("glassShadowSigma"), 2.625f), 0.01f)
        assertEquals(20.50f, ShadowBlur.radiusDp(ThemeArt.m("glassShadowSigma"), 3f), 0.01f)
        assertEquals(0f, ShadowBlur.radiusDp(0f, 3f), 0f)
        assertTrue(ShadowBlur.radiusDp(0f, 2.625f) >= 0f)
    }

    /** D-226 (L36, A1, art.py sections 51 and 53): the glass's film and rim come from the one table. */
    @Test
    fun the_glass_look() {
        assertEquals(ThemeArt.m("glassFilm"), GlassLook.film(blurs = true), 0f)
        assertEquals(ThemeArt.m("glassFilmNoBlur"), GlassLook.film(blurs = false), 0f)
        assertEquals(ThemeArt.m("glassRimLight"), GlassLook.rim(dark = false), 0f)
        assertEquals(ThemeArt.m("glassRimDark"), GlassLook.rim(dark = true), 0f)
        assertNotEquals("the film over the blur is iOS's, not the no-blur one", GlassLook.film(blurs = false), GlassLook.film(blurs = true))
        // Fix wave (F3, F4, art.py 55): Android's shadow is the table's alpha times the measured gain (its dropShadow
        // read 0.58 of iOS's depth); the dark card gets iOS's lift, white over the film; light none.
        assertEquals(1.71f, GlassLook.SHADOW_GAIN, 0f)
        assertEquals(0.06f * 1.71f, GlassLook.shadowAlpha(), 1e-6f)
        assertEquals(0.02f, GlassLook.lift(dark = true), 0f)
        assertEquals(0f, GlassLook.lift(dark = false), 0f)
    }

    /** D-226 (L35, Q18 a): a Norse CARD's edge is drawn on its four straight sides only (notchEdge), the glass card's
     *  notched style and a photo's frame alike; no whole notched outline is stroked in norseEdge on a card shape
     *  anywhere (a control keeps its outline; the hero's edge is pinned in ThemeMotionTest, decision 29). The twin of iOS's
     *  testTheNorseCardEdgeIsTheStraightSides. */
    @Test
    fun the_norse_card_edge_is_the_straight_sides() {
        val art = src("ThemeArt.kt")
        val aurora = src("Aurora.kt")
        val notched = aurora.substringAfter("        CardStyle.NOTCHED -> {").substringBefore("        CardStyle.FRAMED -> {")
        assertEquals("the glass card's notched style", 1, notched.split(".notchEdge(ThemeArt.color(\"norseEdge\"), ThemeArt.m(\"chamfer\"))").size - 1)
        assertFalse("no border there", ".border(" in notched)
        val frame = art.substringAfter("fun Modifier.photoFrame(").substringBefore("\n}\n")
        assertEquals("the photo frame", 1, frame.split("CardStyle.NOTCHED -> clipped.notchEdge(ThemeArt.color(\"norseEdge\"), ThemeArt.m(\"chamfer\"))").size - 1)
        assertFalse("no border there", Regex("""NOTCHED -> [^\n]*\.border\(""").containsMatchIn(frame))
        // Fix wave (final review M1): no `.border(` or `drawOutline(` names norseEdge ANYWHERE in the app, whatever its
        // shape and on however many lines (the old sweep caught only a shape built on the same line, so
        // `.border(1.dp, ThemeArt.color("norseEdge"), shape)`, the old card's own form, passed it). Proved on both forms.
        assertEquals(listOf("border"), wholeOutlineEdges(".border(1.dp, ThemeArt.color(\"norseEdge\"), shape)"))
        assertEquals(listOf("border"), wholeOutlineEdges(".border(\n    1.dp,\n    ThemeArt.color(\"norseEdge\"),\n    NotchedShape(ThemeArt.m(\"chamfer\").dp),\n)"))
        assertEquals(listOf("drawOutline"), wholeOutlineEdges("drawOutline(outline, ThemeArt.color(\"norseEdge\"), style = Stroke(1f))"))
        assertEquals(emptyList<String>(), wholeOutlineEdges(".border(1.dp, Tok.hairline(dark), shape).notchEdge(ThemeArt.color(\"norseEdge\"), 8f)"))
        assertEquals(emptyList<String>(), mainSources().filter { wholeOutlineEdges(it.readText()).isNotEmpty() }.map { it.name })
        // And no edge reaches a draw by another name: every use of the colour in the app is one of these (a new one
        // draws through notchEdge, or extends this list after a look).
        val uses = mainSources().associate { it.name to it.readText().split("\"norseEdge\"").size - 1 }.filterValues { it > 0 }
        // D-245 (decision 197): the welcome's Norse road takes the deep gold for its TRACK (a road, not a card edge; the
        // whole-outline check above still holds), as iOS's `WelcomeProgress.trackColor` does.
        assertEquals(mapOf("Aurora.kt" to 1, "ThemeArt.kt" to 4, "ThemeMotion.kt" to 1, "Welcome.kt" to 1), uses)
        assertTrue("val track = ThemeArt.color(\"norseEdge\").copy(alpha = WelcomeProgress.TRACK_ALPHA)" in src("Welcome.kt"))
        // The edge itself: the straight sides, inset half the 1 dp line, clipped to the notched outline.
        val edge = art.substringAfter("fun Modifier.notchEdge(").substringBefore("\n}\n")
        assertTrue("the four sides", "ArtGeometry.notchEdges(size.width, size.height, chamferDp.dp.toPx(), line / 2)" in edge)
        assertTrue("clipped to the notch", "clipPath(notch)" in edge && "NotchedShape(chamferDp.dp)" in edge)
    }

    /** D-226 (L36, A1): Aurora's glass is iOS's: the shadow through ShadowBlur at the table's sigma, y and alpha in both
     *  modes; the film and the rim from GlassLook; none of D-176's literals left. The twin of iOS's
     *  testTheGlassReadsTheTable. */
    @Test
    fun the_glass_twins_ios() {
        val glass = src("Aurora.kt").substringAfter("        CardStyle.GLASS -> {").substringBefore("        CardStyle.NOTCHED -> {")
        assertTrue("the drop shadow", ".dropShadow(shape) {" in glass)
        assertTrue("its blur", "this.radius = ShadowBlur.radiusDp(ThemeArt.m(\"glassShadowSigma\"), density).dp.toPx()" in glass)
        assertTrue("its offset", "offset = Offset(0f, ThemeArt.m(\"glassShadowY\").dp.toPx())" in glass)
        assertTrue("its alpha (fix wave F3: the table's times Android's gain)", "alpha = GlassLook.shadowAlpha()" in glass && "color = Color.Black" in glass)
        val lift = glass.indexOf(".background(Color.White.copy(alpha = GlassLook.lift(dark)), shape)")
        assertTrue("fix wave F4: the lift over the film, under the tint and the rim",
            lift > glass.indexOf(".backdropGlass(") && lift < glass.indexOf(".background(tint ?: Color.Transparent, shape)") &&
                lift < glass.indexOf(".border(1.dp, Color.White"))
        assertTrue("the film over the blur", "Tok.surface(dark).copy(alpha = GlassLook.film(true))" in glass)
        assertTrue("the film without it", "Tok.surface(dark).copy(alpha = GlassLook.film(false))" in glass)
        assertTrue("the rim", ".border(1.dp, Color.White.copy(alpha = GlassLook.rim(dark)), shape)" in glass)
        for (literal in listOf("0.82f", "0.55f", "0.08f", ".shadow(", "6.dp", "if (dark) 0.dp")) assertFalse("no $literal", literal in glass)
    }

    /** D-226 (controller, from the Task 1 review): the light and dark rims are both 0.35 today, so a swapped mapping
     *  would pass every value test: the mapping is pinned by NAME. */
    @Test
    fun the_glass_rim_maps_dark_to_dark_by_name() {
        val art = src("ThemeArt.kt")
        val look = art.substringAfter("object GlassLook {").substringBefore("\n}\n")
        assertTrue("dark reads glassRimDark, light glassRimLight",
            "fun rim(dark: Boolean): Float = ThemeArt.m(if (dark) \"glassRimDark\" else \"glassRimLight\")" in look)
        assertTrue("blurs reads glassFilm, else glassFilmNoBlur",
            "fun film(blurs: Boolean): Float = ThemeArt.m(if (blurs) \"glassFilm\" else \"glassFilmNoBlur\")" in look)
    }

    /** D-226 (L36): on API 31+ Aurora's glass blurs the page art behind it: the activity's root provides the window's art
     *  holder, the topmost curtains ground records into it only where RenderEffect exists (TabBarGround.blurs), and the
     *  card draws it through C2's backdropGlass clipped to its own shape, never cutting the art out. */
    @Test
    fun the_glass_blurs_the_art_on_31() {
        val glass = src("Aurora.kt").substringAfter("        CardStyle.GLASS -> {").substringBefore("        CardStyle.NOTCHED -> {")
        assertTrue("the card reads the art holder", "val art = LocalArtBackdrop.current" in glass)
        assertTrue("through backdropGlass", ".backdropGlass(\n                            art," in glass)
        assertTrue("clipped to its shape", "shape = shape" in glass)
        assertTrue("never cuts the art out", "active = { false }" in glass)
        val motion = src("ThemeMotion.kt")
        val ground = motion.substringAfter("private fun Modifier.curtainsGround(").substringBefore("\n}\n")
        assertTrue("records only where it can blur", "val art = LocalArtBackdrop.current?.takeIf { TabBarGround.blurs(Build.VERSION.SDK_INT) }" in ground)
        assertTrue("the topmost owns it", "val owns = artOwner(art)" in ground && "owns()" in ground)
        assertTrue("records the art and its one blur", "content.record(" in ground && "art.recordArtBlur(this)" in ground)
        val root = src("AppRoot.kt")
        val content = root.substringAfter("setContent {").substringBefore("    /** D-158:")
        val provides = content.indexOf("CompositionLocalProvider(LocalArtBackdrop provides rememberArtBackdrop())")
        assertTrue("the root provides it", provides >= 0)
        assertTrue("over AppRoot (and so HomeShell)", content.indexOf("AppRoot(", provides) > provides)
        val backdrop = src("Backdrop.kt")
        val glassFn = backdrop.substringAfter("fun Modifier.backdropGlass(").substringBefore("\n}\n")
        assertTrue("the pane takes a shape", "shape: Shape = RectangleShape," in glassFn && "val outline = shape.createOutline(" in glassFn && "drawOutline(outline," in glassFn)
        assertTrue("the shared blur, at the window's size", "artLayer.record(this, layoutDirection, whole) { scale(k, k, Offset.Zero) { drawLayer(shared) } }" in glassFn)
        // Fix wave (M3 b): clipped by a layer's outline (the RenderNode's own round-rect clip), not a Path per draw; a
        // shape with no such outline keeps the path.
        assertTrue("drawn clipped to the card by an outline",
            "if (clipLayer.clipToOutline(outline, r.topLeft)) {" in glassFn && "clipLayer.record(this, layoutDirection, node) { drawLayer(artLayer) }" in glassFn &&
                "drawLayer(clipLayer)" in glassFn)
        assertFalse("no path clip on the art for a card", "val clip = Path().apply { addOutline(outline); translate(r.topLeft) }\n                if (shared" in glassFn)
        val clipFn = backdrop.substringAfter("private fun GraphicsLayer.clipToOutline(").substringBefore("\n}\n")
        assertTrue("a rectangle", "is Outline.Rectangle -> setRectOutline(at + outline.rect.topLeft, outline.rect.size)" in clipFn)
        assertTrue("a round rectangle with one radius",
            "if (!rr.isSimple) return false" in clipFn && "setRoundRectOutline(at + Offset(rr.left, rr.top), Size(rr.width, rr.height), rr.topLeftCornerRadius.x)" in clipFn)
        assertTrue("and clips", "clip = true" in clipFn)
        assertTrue("placed where the card is", "artLayer.translationX = backdrop.sourceOffset.x - origin.x" in glassFn)
        val holder = backdrop.substringAfter("fun rememberArtBackdrop(").substringBefore("\n}\n")
        assertTrue("the art holder blurs only where it can", "TabBarGround.blurs(Build.VERSION.SDK_INT)" in holder && "Backdrop(content, blurred)" in holder)
    }

    /** Fix wave (Task 3 review Important): the art holder's ownership rule, as behaviour. The sources drawing art are
     *  held oldest first: the last attached owns the holder and records (a page under a pushed page never does), one
     *  that leaves hands it to the one under it, wherever it was in the stack, and the last to leave tells the holder
     *  to mark itself unrecorded (so no card draws art that has gone). artOwner keeps no rule of its own. */
    @Test
    fun the_topmost_art_source_owns_the_holder() {
        val owners = mutableListOf<Any>()
        val page = Any()
        val pushed = Any()
        val sheet = Any()
        assertFalse("nobody owns an empty holder", ArtOwners.owns(owners, page))
        ArtOwners.join(owners, page)
        assertTrue("the only one owns", ArtOwners.owns(owners, page))
        ArtOwners.join(owners, pushed)
        assertTrue("the last attached owns", ArtOwners.owns(owners, pushed))
        assertFalse("the page under it never records", ArtOwners.owns(owners, page))
        ArtOwners.join(owners, sheet)
        assertTrue("one leaving from the middle leaves the holder recorded", ArtOwners.leave(owners, pushed))
        assertTrue("and the top still owns", ArtOwners.owns(owners, sheet))
        assertFalse("the one that left owns nothing", ArtOwners.owns(owners, pushed))
        assertTrue("the top leaving hands it back", ArtOwners.leave(owners, sheet))
        assertTrue("to the one under it", ArtOwners.owns(owners, page))
        assertFalse("the last to leave unrecords", ArtOwners.leave(owners, page))
        assertFalse("and nobody owns it", ArtOwners.owns(owners, page))
        val fn = src("Backdrop.kt").substringAfter("internal fun artOwner(").substringBefore("\n}\n")
        assertTrue("joins through the rule", "backdrop?.owners?.let { ArtOwners.join(it, token) }" in fn)
        assertTrue("leaves through it, unrecording when it says",
            "if (backdrop != null && !ArtOwners.leave(backdrop.owners, token)) backdrop.recorded = false" in fn)
        assertTrue("owns through it", "return { backdrop != null && ArtOwners.owns(backdrop.owners, token) }" in fn)
        assertFalse("no rule of its own", "lastOrNull" in fn || "isEmpty" in fn || ".remove(" in fn)
    }

    /** Fix wave (final review M3 a): the art reads only WHETHER a card is over it, through a derived state, never the
     *  card list in its draw: a card entering or leaving composition (a list item scrolling) must not re-record the art
     *  and its blur. */
    @Test
    fun the_art_blur_reads_only_whether_a_card_is_there() {
        val ground = src("ThemeMotion.kt").substringAfter("private fun Modifier.curtainsGround(").substringBefore("\n}\n")
        assertTrue("derived", "val cards = remember(art) { derivedStateOf { art != null && art.panes.isNotEmpty() } }" in ground)
        assertTrue("read in the draw", "if (cards.value) art.recordArtBlur(this)" in ground)
        assertEquals("the list is read only inside the derived state", 1, ground.split("art.panes").size - 1)
    }

    /** D-225 (L-D217-9, art.py 42): the drawn switch's paint by token name (iOS's ThemeArtTests pins the same table) and
     *  where its thumb sits. The negative: OFF carries a border and ON none, so the state never rests on colour alone. */
    @Test
    fun the_switch_paint_and_geometry() {
        fun names(p: SwitchPaint) = Triple(p.track.name, p.border?.name, p.thumb.name)
        assertEquals(Triple("accent", null, "surface"), names(SwitchPaint.of(on = true)))
        assertEquals(Triple("ringTrack", "textFaint", "textDim"), names(SwitchPaint.of(on = false)))
        assertNotNull("off has a border", SwitchPaint.of(on = false).border)
        assertNull("on has none", SwitchPaint.of(on = true).border)
        assertNotEquals(SwitchPaint.of(on = true).thumb, SwitchPaint.of(on = false).thumb)
        assertEquals(15.5f, SwitchGeometry.thumbCentreX(on = false), 1e-6f)
        assertEquals(35.5f, SwitchGeometry.thumbCentreX(on = true), 1e-6f)
        assertEquals("the two places mirror each other in the track", ThemeArt.m("switchWidth"),
            SwitchGeometry.thumbCentreX(on = true) + SwitchGeometry.thumbCentreX(on = false), 1e-6f)
    }

    /** D-224 parity: Norse's check fill and `DoneCheck.tick`, read out of ThemeArt.swift, are Kotlin's, and both are
     *  art.out's (a missing line fails, it never reads as empty). */
    @Test
    fun the_e3_hex_and_tick_match_ios() {
        val ios = Regex(""""norseCheckFill": "(#[0-9A-F]{6})"""").find(swift)?.groupValues?.get(1)
        assertEquals("iOS norseCheckFill", "#18120A", ios)
        assertEquals("Kotlin norseCheckFill", "#18120A", ThemeArt.HEX["norseCheckFill"])
        val line = Regex("""static let tick\b[^=\n]*=\s*\[([^\]\n]*)\]""").find(swift)
        assertTrue("ThemeArt.swift has `static let tick`", line != null)
        val nums = Regex("""-?[0-9]+(?:\.[0-9]+)?""").findAll(line!!.groupValues[1]).map { it.value.toDouble() }.toList()
        val iosTick = nums.chunked(2).map { it[0] to it.getOrElse(1) { Double.NaN } }
        assertEquals("iOS tick", listOf(32.0 to 54.0, 46.0 to 67.0, 73.0 to 39.0), iosTick)
        assertEquals("Kotlin tick", iosTick, DoneCheck.TICK)
    }

    /** D-223 parity: iOS's `LucideGlyph.inbox` (read from LucideIcon.swift's switch) is Kotlin's, and both are upstream's
     *  (lucide-static icons/inbox.svg: its polyline as M then L through the points in order, then its path verbatim). */
    @Test
    fun the_lucide_inbox_matches_ios() {
        val upstream = listOf(
            "M22 12 L16 12 L14 15 L10 15 L8 12 L2 12",
            "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
        )
        val lucide = java.io.File("../../ios/Skipti/App/LucideIcon.swift").readText()
        val switchBody = lucide.substringAfter("var pathData: [String] {").substringBefore("func path() -> Path {")
            .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        val at = switchBody.indexOf("case .inbox:")
        assertTrue("iOS draws inbox", at >= 0)
        val rest = switchBody.substring(at + "case .inbox:".length)
        val end = Regex("""case \.\w+:|default:""").find(rest)?.range?.first ?: rest.length
        val ios = Regex("\"([^\"]+)\"").findAll(rest.substring(0, end)).map { it.groupValues[1] }.toList()
        assertEquals("iOS", upstream, ios)
        assertEquals("Kotlin", upstream, TabIcons.PATHS["inbox"])
        assertEquals(24f, TabIcons.Inbox.viewportWidth, 0f)
    }

    /** D-221 (Q2 a, K05, K06): Home's badge takes the accent fill, every other avatar the soft one; Norse alone keeps
     *  an edge (the hairline is gone elsewhere). */
    @Test
    fun avatar_fills_and_edge() {
        assertEquals(AvatarFill.ACCENT, AvatarStyle.fill(badge = true))
        assertEquals(AvatarFill.SOFT, AvatarStyle.fill(badge = false))
        assertEquals(Tok.accentSoft to Tok.accent, AvatarFill.SOFT.ground to AvatarFill.SOFT.ink)
        assertEquals(Tok.accent to Tok.onAccent, AvatarFill.ACCENT.ground to AvatarFill.ACCENT.ink)
        assertEquals(listOf(null, "#6B5426", null), AppTheme.entries.map(AvatarStyle::edgeHex))
    }

    /** D-221 (Q2 a, K05, K06): every avatar draws its fill's ground and ink (soft by default, the accent for the
     *  bar's badge), Norse's edge alone (no hairline, no raised disc), the initials in the decision's face. */
    @Test
    fun avatars_take_their_fill() {
        val view = funBody("Profile.kt", "fun AvatarView(")
        assertTrue("soft by default", "fill: AvatarFill = AvatarStyle.fill(badge = false)" in view)
        assertTrue("the fill's ground", ".background(fill.ground(dark))" in view)
        assertTrue("the edge by the decision", "AvatarStyle.edgeHex(Appearance.theme)" in view)
        assertTrue("initials in the decision's face, in the ink", "AvatarStyle.initialStyle(dark, size.value).copy(color = fill.ink(dark))" in view)
        assertTrue("the glyph in the ink", "tint = fill.ink(dark)" in view)
        assertFalse("no hairline", "Tok.hairline" in view)
        assertFalse("no raised disc", "Tok.surfaceRaised" in view)
        assertFalse("no ring parameter", "ring: Color?" in view)
        val glyph = funBody("AppRoot.kt", "internal fun AccountGlyph(")
        assertTrue("the badge takes the accent", "fill = AvatarStyle.fill(badge = true)" in glyph)
        assertFalse("and nothing else", "AvatarFill.SOFT" in glyph || "BarDecisions.accountBadge" in glyph)
    }

    /** D-221 (Design 10): the compact primary keeps the look (both branches, the shape, the dimming) and drops the
     *  full width, at the label's 15 and padding 16 / 10. */
    @Test
    fun compact_primary_keeps_the_look() {
        val body = funBody("GroupScreens.kt", "internal fun PrimaryAction(")
        assertTrue("compact is a parameter", "compact: Boolean = false," in body)
        assertTrue("compact does not fill the width", "modifier: Modifier = if (compact) Modifier else Modifier.fillMaxWidth()," in body)
        assertTrue("the look still branches", "ButtonLook.of(Appearance.theme) == ButtonLook.OUTLINED" in body)
        assertEquals("both looks dim", 2, body.split(".alpha(if (enabled) 1f else ButtonLook.DISABLED_ALPHA)").size - 1)
        assertTrue("the shape", "shape = ThemeArt.controlShape(Appearance.theme)" in body)
        assertTrue("the compact padding", "PaddingValues(horizontal = 16.dp, vertical = 10.dp)" in body)
        assertTrue("the compact label", "if (compact) 15 else 17" in body)
        assertEquals("both looks take the padding", 2, body.split("contentPadding = padding").size - 1)
    }

    /** D-221: the milestone's seal is the theme's tile (Aurora a circle, Norse the diamond, Vardr Labs a square); the
     *  progress pill's dot is Vardr Labs' alone. */
    @Test
    fun seal_and_dot_per_theme() {
        assertEquals(listOf(TileShape.CIRCLE, TileShape.DIAMOND, TileShape.SQUARE), AppTheme.entries.map(SealStyle::clip))
        assertEquals(listOf(false, false, true), AppTheme.entries.map(ProgressPill::showsDot))
    }

    /** D-226: at the card's chamfer, 10 (8 x 1.28); the golden is .drafts/d226/art.py section 46's (8 before). */
    @Test
    fun the_notched_cards_vertices() {
        assertEquals(
            listOf(Offset(10f, 0f), Offset(90f, 0f), Offset(100f, 10f), Offset(100f, 50f), Offset(90f, 60f), Offset(10f, 60f), Offset(0f, 50f), Offset(0f, 10f)),
            ArtGeometry.notched(100f, 60f, ThemeArt.m("chamfer")),
        )
    }

    @Test
    fun the_chamfer_never_passes_half_the_short_side() {
        assertEquals(
            listOf(Offset(5f, 0f), Offset(5f, 0f), Offset(10f, 5f), Offset(10f, 5f), Offset(5f, 10f), Offset(5f, 10f), Offset(0f, 5f), Offset(0f, 5f)),
            ArtGeometry.notched(10f, 10f, ThemeArt.m("chamfer")),
        )
    }

    // ---- D-243 (T7, plan section 5 test 9): Norse's card-edge glow, the twin of iOS's ----

    /** D-243 fix wave (I1): floored page-ground text keeps at least the glow's reach ([ArtGeometry.glowReach]) from a Norse
     *  card edge: every glow band fails textDim over a flaring rune (motion.py 1f). The twin of iOS's tightest site, Add a
     *  person (People.kt: its labels sit in the fields, the sheet's items 12 apart, "Recently with you" above its cards),
     *  and the closest of the 8 dp sites (.drafts/d243/gap-audit.md), Appearance's Text size label above its card, are
     *  read from the source and held to it. */
    @Test
    fun page_text_keeps_the_glows_reach_from_a_norse_card() {
        fun spacing(file: String, before: String, after: String): Float {
            val source = src(file)
            assertEquals("$file: one site", 1, source.split(before).size - 1)
            val tail = source.substringAfter(before)
            val number = tail.substringBefore(after)
            assertTrue("$file: the spacing, then what it separates", tail.startsWith(number + after))
            return requireNotNull(number.toFloatOrNull()) { "$file: the spacing is a number: $number" }
        }
        val addPerson = spacing("People.kt", "AddPersonCopy.TITLE, dark, onCancel = onBack, spacing = ", ".dp,")
        assertTrue("Add a person's items clear the glow", addPerson >= ArtGeometry.glowReach())
        val textSize = spacing(
            "AccountHubScreen.kt",
            "SettingItem.TEXT_SIZE -> Column {\n                    SectionLabel(AppearanceCopy.CARDS[2], dark)\n                    Spacer(Modifier.height(",
            ".dp))\n                    Card(dark) {",
        )
        assertTrue("the Text size label clears the glow", textSize >= ArtGeometry.glowReach())
        assertEquals("motion.py's CLEAR DISTANCE", 6f, ArtGeometry.glowReach(), 0f)
    }

    /** The glow's bands are motion.py's (.drafts/d243/motion-lanes.out section 6, `edge bands`): 0.5 dp bands at
     *  0.18 x Phi(-d / 3.0) (sigma 3.0 after the gap audit, .drafts/d243/motion-t7.out), twelve of them, the last at least one
     *  8-bit step and the next (6.25 dp, 0.003350) under it; 6 dp of reach. The ported erfc against Python's math.erfc. The negatives: no peak, sigma or band width gives no
     *  glow; a tighter sigma reaches less. */
    @Test
    fun the_edge_glow_bands_are_motion_pys() {
        val want = listOf(
            0.25 to 0.084023, 0.75 to 0.072233, 1.25 to 0.060923, 1.75 to 0.05037, 2.25 to 0.040793, 2.75 to 0.032339,
            3.25 to 0.025079, 3.75 to 0.019017, 4.25 to 0.014092, 4.75 to 0.010201, 5.25 to 0.007211, 5.75 to 0.004975,
        )
        val bands = ArtGeometry.glowBands()
        assertEquals(want.size, bands.size)
        for ((b, w) in bands.zip(want)) {
            assertEquals(w.first.toFloat(), b.first, 0f)
            assertEquals("band ${w.first}", w.second, b.second, 6e-7)
        }
        bands.zipWithNext().forEach { (a, b) -> assertTrue("the glow falls outward", b.second < a.second) }
        assertTrue(bands.last().second >= 1.0 / 255)
        assertTrue("the next band is under a step", 0.18 * ArtGeometry.erfc(6.25 / (3.0 * sqrt(2.0))) / 2 < 1.0 / 255)
        assertEquals(6f, ArtGeometry.glowReach(), 0f)
        // Python's math.erfc (independent): 0, 0.5, 1, 2, 3 and a negative.
        for ((x, e) in listOf(0.0 to 1.0, 0.5 to 0.4795001221869535, 1.0 to 0.15729920705028516, 2.0 to 0.0046777349810472645,
            3.0 to 2.2090496998585438e-05, -1.0 to 1.8427007929497148)) {
            assertEquals("erfc($x)", e, ArtGeometry.erfc(x), e * 1.2e-7 + 1e-15)
        }
        assertTrue(ArtGeometry.glowBands(peak = 0.0).isEmpty())
        assertTrue(ArtGeometry.glowBands(sigma = 0.0).isEmpty())
        assertTrue(ArtGeometry.glowBands(band = 0.0).isEmpty())
        assertEquals("motion.py at sigma 2: 4 dp", 8, ArtGeometry.glowBands(sigma = 2.0).size)
    }

    /** The grown outline: vertices from .drafts/d243/t7/geometry.py (each edge line of the 100 x 60 card offset outward
     *  and neighbours intersected, not this closed form), and every side of every band's outline, the 45 degree cuts
     *  included, exactly its offset from the card's matching side. The negative: the plan's first formula,
     *  c + d (sqrt 2 - 1), leaves the cuts 1.12 d out. The chamfer is clamped before it grows. */
    @Test
    fun the_glows_outline_grows_every_side_by_its_offset() {
        val c = ThemeArt.m("chamfer")
        val goldens = listOf(
            0.25f to listOf(9.896447f to -0.25f, 90.103553f to -0.25f, 100.25f to 9.896447f, 100.25f to 50.103553f,
                90.103553f to 60.25f, 9.896447f to 60.25f, -0.25f to 50.103553f, -0.25f to 9.896447f),
            5.75f to listOf(7.618272f to -5.75f, 92.381728f to -5.75f, 105.75f to 7.618272f, 105.75f to 52.381728f,
                92.381728f to 65.75f, 7.618272f to 65.75f, -5.75f to 52.381728f, -5.75f to 7.618272f),
        )
        for ((d, want) in goldens) {
            val p = ArtGeometry.grownNotched(100f, 60f, c, d)
            assertEquals(8, p.size)
            for ((a, b) in p.zip(want)) {
                assertEquals("d $d", b.first, a.x, 1e-4f)
                assertEquals("d $d", b.second, a.y, 1e-4f)
            }
        }
        val base = ArtGeometry.notched(100f, 60f, c)
        fun distance(p: Offset, a: Offset, b: Offset): Double {
            val (ax, ay, bx, by) = listOf(a.x, a.y, b.x, b.y).map { it.toDouble() }
            return abs((bx - ax) * (ay - p.y) - (ax - p.x) * (by - ay)) / hypot(bx - ax, by - ay)
        }
        for ((d, _) in ArtGeometry.glowBands()) {
            val g = ArtGeometry.grownNotched(100f, 60f, c, d)
            for (i in 0 until 8) {
                val j = (i + 1) % 8
                assertEquals("side $i at $d", d.toDouble(), distance(g[i], base[i], base[j]), 1e-4)
                assertEquals("side $i at $d", d.toDouble(), distance(g[j], base[i], base[j]), 1e-4)
            }
        }
        val wrong = ArtGeometry.notched(108f, 68f, c + 4 * (sqrt(2f) - 1)).map { Offset(it.x - 4, it.y - 4) }
        assertEquals("the plan's formula: 1.12 d", 4.485, distance(wrong[7], base[7], base[0]), 0.001)
        val small = ArtGeometry.grownNotched(10f, 10f, c, 1f)
        assertEquals("clamped to 5, then grown", -1f + 5f + (2f - sqrt(2f)), small[0].x, 1e-5f)
        assertEquals(-1f, small[0].y, 0f)
    }

    /** The Norse cards draw the glow: glassCard's NOTCHED branch first in its chain (beneath the fill), the hero's NORSE
     *  chain first (outside its clipped art); GLASS and FRAMED never; the glow is drawn bands built once per size, never a
     *  blur or a shadow. */
    @Test
    fun the_norse_cards_draw_the_edge_glow() {
        val aurora = src("Aurora.kt")
        val card = aurora.substringAfter("fun Modifier.glassCard(").substringBefore("\n}\n")
        val notched = card.substringAfter("CardStyle.NOTCHED -> {").substringBefore("CardStyle.FRAMED ->")
        assertEquals(1, notched.split(".norseEdgeGlow(Tok.accent(dark), ThemeArt.m(\"chamfer\"))").size - 1)
        assertTrue("beneath the fill", notched.indexOf(".norseEdgeGlow(") < notched.indexOf(".background(Brush.verticalGradient("))
        for (banned in listOf(".shadow(", "dropShadow", "blur", "BlurEffect")) assertFalse("no $banned in the Norse card", banned in notched)
        assertFalse("GLASS", "EdgeGlow" in card.substringAfter("CardStyle.GLASS -> {").substringBefore("CardStyle.NOTCHED ->"))
        assertFalse("FRAMED", "EdgeGlow" in card.substringAfter("CardStyle.FRAMED -> {"))
        val hero = src("ThemeMotion.kt").substringAfter("fun Modifier.heroArt(").substringAfter("AppTheme.NORSE -> {").substringBefore("AppTheme.VARDR ->")
        val chain = hero.substringAfter("            Modifier\n")
        assertTrue("the hero's glow is first, outside its clip", chain.trimStart().let { it.startsWith("// D-243 (T7)") || it.startsWith(".norseEdgeGlow(") })
        assertTrue(chain.indexOf(".norseEdgeGlow(accent, ThemeArt.m(\"chamfer\"))") in 0 until chain.indexOf(".drawBehind {"))
        val art = src("ThemeArt.kt")
        val glow = art.substringAfter("fun Modifier.norseEdgeGlow(").substringBefore("\n}\n")
        for (needle in listOf("drawWithCache {", "ArtGeometry.glowBands()", "ArtGeometry.grownNotched(size.width, size.height, chamfer, d.dp.toPx())",
            "join = StrokeJoin.Miter", "onDrawBehind {", "alpha = bands[i].second.toFloat()")) assertTrue(needle, needle in glow)
        // The paths are built in the cache block, none in the draw.
        assertFalse("no path per draw", "roundedPolygon" in glow.substringAfter("onDrawBehind"))
        for (banned in listOf(".shadow(", "dropShadow", "blur", "Brush.")) assertFalse(banned, banned in glow)
        // Every use: the card, the hero and the ledger card (its ripple's clip would cut glassCard's glow, so it draws
        // one before the clip, Norse only), nowhere else (the definition aside).
        val files = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles()!!.filter { it.name.endsWith(".kt") }
        val uses = files.associate { it.name to it.readText().replace("fun Modifier.norseEdgeGlow(", "").split(".norseEdgeGlow(").size - 1 }
            .filterValues { it > 0 }
        assertEquals(mapOf("Aurora.kt" to 1, "ThemeMotion.kt" to 1, "HomeParts.kt" to 1), uses)
        val ledger = src("HomeParts.kt").substringAfter("fun LedgerCard(").substringBefore(".glassCard(dark)")
        assertTrue("the ledger card's glow is Norse's alone, before its clip",
            ledger.indexOf("if (CardStyle.of(Appearance.theme) == CardStyle.NOTCHED) Modifier.norseEdgeGlow(") in 0 until ledger.indexOf(".clip("))
        // The three keys are appended last, in motion.py's order (D-216's preview pair before them).
        assertEquals(listOf("norseEdgeGlow", "norseEdgeGlowSigma", "norseEdgeGlowBand"), ThemeArt.METRICS.keys.toList().takeLast(3))
    }

    @Test
    fun the_diamond_and_hexagon_vertices() {
        assertEquals(listOf(Offset(24f, 0f), Offset(48f, 24f), Offset(24f, 48f), Offset(0f, 24f)), ArtGeometry.diamond(48f, 48f))
        val want = listOf(Offset(18f, 1.28f), Offset(54f, 1.28f), Offset(72f, 32f), Offset(54f, 62.72f), Offset(18f, 62.72f), Offset(0f, 32f))
        val got = ArtGeometry.hexagon(72f, 64f)
        assertEquals(want.size, got.size)
        for ((a, b) in got.zip(want)) {
            assertEquals(b.x, a.x, 0.0001f)
            assertEquals(b.y, a.y, 0.0001f)
        }
    }

    /** D-226: at the art's frameInset 4 and frameLength 12 (3 and 8 before); .drafts/d226/art.py section 46, all eight. */
    @Test
    fun the_frame_marks() {
        val s = ArtGeometry.frameMarks(100f, 60f, ThemeArt.m("frameInset"), ThemeArt.m("frameLength"))
        assertEquals(
            listOf(
                Offset(4f, 4f) to Offset(16f, 4f), Offset(4f, 4f) to Offset(4f, 16f),
                Offset(96f, 4f) to Offset(84f, 4f), Offset(96f, 4f) to Offset(96f, 16f),
                Offset(96f, 56f) to Offset(84f, 56f), Offset(96f, 56f) to Offset(96f, 44f),
                Offset(4f, 56f) to Offset(16f, 56f), Offset(4f, 56f) to Offset(4f, 44f),
            ),
            s,
        )
        for ((a, b) in s) for (p in listOf(a, b)) assertTrue(p.x in 0f..100f && p.y in 0f..60f)
    }

    @Test
    fun the_grids_dots() {
        assertEquals(listOf(Offset(7f, 7f), Offset(21f, 7f), Offset(7f, 21f), Offset(21f, 21f)), ArtGeometry.gridDots(28f, 30f, 14f))
        // A centre ON the edge is not inside: 21 x 21 holds one dot, (7, 7), not four (21 is not < 21).
        assertEquals(listOf(Offset(7f, 7f)), ArtGeometry.gridDots(21f, 21f, 14f))
        assertEquals(1798, ArtGeometry.gridDots(402f, 874f, 14f).size)
        assertTrue(ArtGeometry.gridDots(0f, 0f, 14f).isEmpty())
    }

    @Test
    fun each_themes_decisions() {
        val themes = AppTheme.entries
        assertEquals(listOf(TabIndicator.PILL, TabIndicator.UNDERLINE, TabIndicator.OUTLINE), themes.map(TabIndicator::of))
        assertEquals(listOf(false, true, false), themes.map(TabIndicator::drawsRunes))
        // D-219 (L13): Aurora's pill is filled accent, so its glyph is onAccent; Norse keeps the gold; Vardr Labs the ring.
        assertEquals(listOf(TabGlyphTint.ON_ACCENT, TabGlyphTint.ACCENT, TabGlyphTint.RING), themes.map(TabIndicator::activeGlyph))
        // D-219 (L15): Aurora's bar is glass; Norse and Vardr Labs are solid surface.
        assertEquals(listOf(TabBarGround.GLASS, TabBarGround.SOLID, TabBarGround.SOLID), themes.map(TabBarGround::of))
        assertEquals(0.94f, TabBarGround.FILM_ALPHA, 0f)
        assertEquals(listOf(AddButtonArt.CIRCLE, AddButtonArt.DIAMOND, AddButtonArt.BOLT), themes.map(AddButtonArt::of))
        assertEquals(listOf(CardStyle.GLASS, CardStyle.NOTCHED, CardStyle.FRAMED), themes.map(CardStyle::of))
        assertEquals(listOf(TileShape.ROUNDED, TileShape.DIAMOND, TileShape.SQUARE), themes.map { TileShape.of(it, person = false) })
        assertEquals(listOf(TileShape.CIRCLE, TileShape.DIAMOND, TileShape.SQUARE), themes.map { TileShape.of(it, person = true) })
        assertEquals(listOf(ButtonLook.FILLED, ButtonLook.FILLED, ButtonLook.OUTLINED), themes.map(ButtonLook::of))
        assertEquals(listOf(Ground.CURTAINS, Ground.RUNES, Ground.GRID), themes.map(Ground::of))
        assertEquals(listOf(null, "\u16DF ", null), themes.map(SectionMark::prefix))
    }

    @Test
    fun the_tab_outline_alpha_is_the_measured_one() {
        assertEquals(0.75f, TabIndicator.outlineAlpha(ThemeSet.VARDR_LIGHT), 0f)
        assertEquals(0.5f, TabIndicator.outlineAlpha(ThemeSet.VARDR_DARK), 0f)
    }

    @Test
    fun the_separator_follows_the_theme() {
        assertEquals(" \u00B7 ", Separator.meta(AppTheme.AURORA))
        assertEquals(" \u16EB ", Separator.meta(AppTheme.NORSE))
        assertEquals(" \u00B7 ", Separator.meta(AppTheme.VARDR))
        Appearance.setForTest(theme = AppTheme.NORSE)
        assertEquals(" \u16EB ", Separator.meta())
    }

    @Test
    fun a_styled_line_colours_only_the_norse_separator() {
        val norse = Separator.annotated(listOf("Goa", "2 people", "INR"), AppTheme.NORSE)
        assertEquals("Goa \u16EB 2 people \u16EB INR", norse.text)
        // As on iOS the accent covers the whole " rune " (spaces included); only the rune character takes the font.
        val accent = coloured(norse).map { norse.text.substring(it.start, it.end) }
        assertEquals(listOf(" \u16EB ", " \u16EB "), accent)
        assertTrue(coloured(norse).all { it.item.color == Tok.accent.norse })
        assertEquals(listOf("\u16EB", "\u16EB"), runeSpans(norse).map { norse.text.substring(it.start, it.end) })
        // A rune inside a part (a category label's own separator) takes the font and keeps the line's colour.
        val nested = Separator.annotated(listOf("Goa", "Food \u16EB Dining"), AppTheme.NORSE)
        assertEquals(listOf(" \u16EB "), coloured(nested).map { nested.text.substring(it.start, it.end) })
        assertEquals(listOf("\u16EB", "\u16EB"), runeSpans(nested).map { nested.text.substring(it.start, it.end) })
        val aurora = Separator.annotated(listOf("Goa", "INR"), AppTheme.AURORA)
        assertEquals("Goa \u00B7 INR", aurora.text)
        assertTrue(aurora.spanStyles.isEmpty())
        assertEquals("", Separator.annotated(emptyList(), AppTheme.NORSE).text)
    }

    /** The spans that carry a colour, and the spans that carry the rune font. */
    private fun coloured(a: androidx.compose.ui.text.AnnotatedString) = a.spanStyles.filter { it.item.color != androidx.compose.ui.graphics.Color.Unspecified }

    private fun runeSpans(a: androidx.compose.ui.text.AnnotatedString) = a.spanStyles.filter { it.item.fontFamily != null }

    @Test
    fun every_art_value_is_present() {
        assertEquals(
            setOf(
                "norseEdge", "norseCardTop", "norseCardBottom", "norseSilver", "diamondTop", "diamondBottom",
                "boltDark0", "boltDark1", "boltDark2", "boltDark3", "boltLight0", "boltLight1", "boltLight2", "boltLight3",
                "runeUnlit", "norseMeterGap", "norseCheckFill",
            ),
            ThemeArt.HEX.keys,
        )
        assertEquals(listOf(0f, 0.38f, 0.62f, 1f), ThemeArt.boltStops(dark = true).map { it.first })
        assertEquals(listOf(0f, 0.45f, 0.70f, 1f), ThemeArt.boltStops(dark = false).map { it.first })
        assertEquals(0.18f, ThemeArt.reticleAlpha(dark = true), 0f)
        assertEquals(0.25f, ThemeArt.reticleAlpha(dark = false), 0f)
        assertNull(ThemeArt.HEX["missing"])
        // D-219: reticleGlyph added; D-220: pillChamfer and pillOutline: 29 sizes and fractions, and the diamond's side
        // is the circle's square. D-221: 15 more (the ring, the meter, the dot, the seal, the tile): 44. D-222: 25 more: 69.
        // D-223: 9 more (Home's ledgers and Lately, the empty state, the Groups card): 78. D-224: 16 more (the done
        // check and the toast): 94. D-225: 16 more (the list row, the switch, the emoji grid, the lock, the cross): 110.
        // D-226: 12 more (the Norse hero's frame and watermark, Aurora's glass): 122; pillChamfer 5 on the scaled chamfer.
        // D-216: 2 more (the App icon rows' preview tile): 124. D-243 (T7): 3 more (Norse's card-edge glow): 127.
        assertEquals(127, ThemeArt.METRICS.size)
        assertEquals(5f, ThemeArt.m("pillChamfer"), 0f)
        assertEquals(0.4f, ThemeArt.m("pillOutline"), 0f)
        assertEquals(64f, ThemeArt.m("diamondSide"), 0f)
        assertEquals(32f, ThemeArt.m("reticleGlyph"), 0f)
    }

    /** Reads `static let <name>: CGFloat = <number>` out of iOS's LucideIcon.swift; a missing line fails. */
    private fun swiftStroke(name: String): Float {
        val lucide = java.io.File("../../ios/Skipti/App/LucideIcon.swift").readText()
        val m = Regex("""static let $name: CGFloat = ([0-9.]+)""").find(lucide)
        assertTrue("iOS LucideStroke.$name parsed", m != null)
        return m!!.groupValues[1].toFloat()
    }

    /** D-219 (L18): the Look Book draws its icons at stroke-width 2.4 and its plus at 2.8. */
    @Test
    fun the_lucide_stroke_is_the_bolder_one() {
        assertEquals(2.4f, LucideStroke.WIDTH, 0f)
        assertEquals(2.8f, LucideStroke.PLUS, 0f)
        // Every TabIcons vector is drawn at that stroke (the plus at the heavier one), round caps and joins, no fill.
        val vectors = mapOf(
            "Wallet" to TabIcons.Wallet, "Users" to TabIcons.Users, "UserPlus" to TabIcons.UserPlus,
            "ChevronLeft" to TabIcons.ChevronLeft, "ChevronRight" to TabIcons.ChevronRight, "CircleCheck" to TabIcons.CircleCheck,
            "UserRound" to TabIcons.UserRound, "Receipt" to TabIcons.Receipt, "Plus" to TabIcons.Plus,
            "Search" to TabIcons.Search, "Check" to TabIcons.Check, "Inbox" to TabIcons.Inbox,
        )
        for ((name, vector) in vectors) {
            val paths = vector.root.filterIsInstance<androidx.compose.ui.graphics.vector.VectorPath>()
            assertTrue("$name has paths and nothing else", paths.isNotEmpty() && paths.size == vector.root.asSequence().count())
            val want = if (name == "Plus") LucideStroke.PLUS else LucideStroke.WIDTH
            for (path in paths) {
                assertEquals("$name stroke", want, path.strokeLineWidth, 0f)
                assertEquals("$name cap", androidx.compose.ui.graphics.StrokeCap.Round, path.strokeLineCap)
                assertEquals("$name join", androidx.compose.ui.graphics.StrokeJoin.Round, path.strokeLineJoin)
                assertNull("$name has no fill", path.fill)
            }
        }
        val icons = src("TabIcons.kt")
        assertFalse("no literal 2f stroke is left in TabIcons.kt", "strokeLineWidth = 2f" in icons)
        assertTrue("lucide() takes the constant by default", "stroke: Float = LucideStroke.WIDTH" in icons)
        assertTrue("the plus is built at the heavier stroke", "stroke = LucideStroke.PLUS" in icons)
    }

    /** Parity: iOS's `LucideStroke.width` and `.plus` (read from LucideIcon.swift) equal Kotlin's. */
    @Test
    fun the_lucide_stroke_matches_ios() {
        assertEquals(swiftStroke("width"), LucideStroke.WIDTH, 0f)
        assertEquals(swiftStroke("plus"), LucideStroke.PLUS, 0f)
    }

    /** D-219 (L17): the twin of iOS's testThePlusFootprintAndFaces. */
    @Test
    fun the_plus_footprint_and_faces() {
        assertEquals(64f, AddButtonGeometry.FOOTPRINT, 0f)
        assertEquals(18f, AddButtonGeometry.LIFT, 0f)
        // D-219 fix wave (M-2): read across, so a one-sided edit of a constant and its own test cannot pass.
        val iosFootprint = Regex("""static let footprint: CGFloat = (\d+)""").find(swift)
        val iosLift = Regex("""static let lift: CGFloat = (\d+)""").find(swift)
        assertTrue("iOS footprint parsed", iosFootprint != null)
        assertTrue("iOS lift parsed", iosLift != null)
        assertEquals(iosFootprint!!.groupValues[1].toFloat(), AddButtonGeometry.FOOTPRINT, 0f)
        assertEquals(iosLift!!.groupValues[1].toFloat(), AddButtonGeometry.LIFT, 0f)
        assertEquals(64f to 64f, AddButtonGeometry.faceSize(AddButtonArt.CIRCLE))
        assertEquals(72f to 64f, AddButtonGeometry.faceSize(AddButtonArt.BOLT))
        val diamond = AddButtonGeometry.faceSize(AddButtonArt.DIAMOND)
        assertEquals(90.51f, diamond.first, 0.01f)
        assertEquals(90.51f, diamond.second, 0.01f)
        assertEquals(ThemeArt.m("diamondSide") * kotlin.math.sqrt(2f), diamond.first, 1e-4f)
        assertEquals(50f, AddButtonGeometry.FOOTPRINT / 2 + AddButtonGeometry.LIFT, 0f)
        assertEquals(ThemeArt.m("boltWidth") to ThemeArt.m("boltHeight"), AddButtonGeometry.faceSize(AddButtonArt.BOLT))
        // The diamond clears its neighbours at 320 wide: five slots, the 22 dp glyph at 1.08, a 12 dp side padding.
        val reach = ThemeArt.m("diamondSide") * kotlin.math.sqrt(2f) / 2
        val neighbourEdge = (320f - 12f) / 5f - 11f * 1.08f
        assertEquals(45.25f, reach, 0.01f)
        assertEquals(49.72f, neighbourEdge, 0.01f)
        assertTrue(reach < neighbourEdge)
        // The negative: the old side (50) is not the diamond's.
        assertTrue(ThemeArt.m("diamondSide") != 50f)
        // The faces are laid out at their own size whatever the slot's constraints (`size` is squeezed to the slot:
        // the old 70.71 diamond drew 69.6 on a 360 dp phone), all inside the one footprint box.
        val face = faceSource()
        assertTrue("the footprint box", "Box(modifier.size(AddButtonGeometry.FOOTPRINT.dp), contentAlignment = Alignment.Center)" in face)
        for (branch in listOf("AddButtonArt.CIRCLE ->", "AddButtonArt.DIAMOND ->", "AddButtonArt.BOLT ->")) {
            val body = faceBranch(face, branch)
            assertTrue("$branch: requiredSize(", ".requiredSize(" in body)
            assertFalse("$branch: no squeezable .size(", Regex("""\.size\(""").containsMatchIn(body))
        }
        assertTrue("the diamond's from the geometry", "AddButtonGeometry.faceSize(AddButtonArt.DIAMOND)" in faceBranch(face, "AddButtonArt.DIAMOND ->"))
        assertTrue("the circle's from the geometry", "AddButtonGeometry.faceSize(AddButtonArt.CIRCLE)" in faceBranch(face, "AddButtonArt.CIRCLE ->"))
        assertTrue("the bolt's from the geometry", "AddButtonGeometry.faceSize(AddButtonArt.BOLT)" in faceBranch(face, "AddButtonArt.BOLT ->"))
    }

    /** D-219 fix wave (look defect 2): Android's diamond is as large as iOS's. iOS strokes its ring 4 centred on the rounded
     *  path (`shape.stroke(Tokens.bg, lineWidth: 4)`, so half lies outside); Android's `border` drew it inside and made the
     *  diamond about 5 dp smaller across. The extent is pinned from an independent derivation of the quadratic corner. */
    @Test
    fun the_diamond_is_drawn_as_large_as_ios() {
        val box = AddButtonGeometry.faceSize(AddButtonArt.DIAMOND).first
        // The top tip, rebuilt here: the curve starts diamondRadius along each edge (ArtGeometry.roundedPolygon) and its
        // control point is the vertex, so the apex (t = 0.5) is 0.25 a + 0.5 v + 0.25 b. D-226: diamondRadius 8 (6
        // before: apex 2.12, path 86.27, extent 90.27, iOS's measured 90.5); the new numbers are .drafts/d226/art.py
        // section 46's.
        val v = ArtGeometry.diamond(box, box)[0]
        val next = ArtGeometry.diamond(box, box)[1]
        val len = kotlin.math.hypot(next.x - v.x, next.y - v.y)
        val ay = v.y + (next.y - v.y) * ThemeArt.m("diamondRadius") / len
        val apexY = 0.25f * ay + 0.5f * v.y + 0.25f * ay
        assertEquals(2.83f, apexY, 0.01f)
        val path = box - 2 * apexY
        assertEquals(84.85f, path, 0.01f)
        // Outer extent: the path plus a centred 4 dp ring (2 outside each side).
        assertEquals(88.85f, AddButtonGeometry.diamondOuterExtent(), 0.01f)
        assertEquals(path + 4f, AddButtonGeometry.diamondOuterExtent(), 0.01f)
        // iOS draws the same ring: a stroke of the same width, not a strokeBorder (which would sit inside).
        val ios = Regex("""\.overlay\(shape\.stroke\(Tokens\.bg, lineWidth: (\d+)\)\)""").find(swift)
        assertTrue("iOS's diamond ring parsed", ios != null)
        assertEquals(ios!!.groupValues[1].toFloat(), AddButtonGeometry.DIAMOND_RING, 0f)
        // Source guard: the diamond draws the ring as a centred stroke of the constant, and no inside border.
        val body = faceBranch(faceSource(), "AddButtonArt.DIAMOND ->")
        assertTrue("a centred stroke of the ring width", "style = Stroke(width = AddButtonGeometry.DIAMOND_RING.dp.toPx())" in body)
        assertFalse("no inside border", ".border(" in body)
        assertTrue("the shadow does not clip the ring", ".shadow(AddButtonShadow.ELEVATION.dp, shape, clip = false)" in body)
        assertTrue("the tap's shape clips after the drawing", body.indexOf(".drawBehind") in 0 until body.indexOf(".clip(shape)"))
    }

    /** The source of `AddButtonFace` (one function; ends at the first closing brace in column 0). */
    private fun faceSource(): String {
        val art = src("ThemeArt.kt")
        val start = art.indexOf("fun AddButtonFace(")
        return art.substring(start, art.indexOf("\n}\n", start))
    }

    /** One `when` branch of the face: from [branch] to the next branch (or the end). */
    private fun faceBranch(face: String, branch: String): String {
        val order = listOf("AddButtonArt.CIRCLE ->", "AddButtonArt.DIAMOND ->", "AddButtonArt.BOLT ->")
        val from = face.indexOf(branch)
        assertTrue("$branch present", from >= 0)
        val to = order.getOrNull(order.indexOf(branch) + 1)?.let { face.indexOf(it) } ?: face.length
        assertTrue("$branch ends after it starts", to > from)
        return face.substring(from, to)
    }

    /** D-219 (L16): the circle's shadow is neutral: elevation 7 dp, the default black colours (iOS: 0.2, radius 7, y 6). */
    @Test
    fun the_plus_shadow_is_neutral() {
        assertEquals(7f, AddButtonShadow.ELEVATION, 0f)
        val face = faceSource()
        assertFalse("no spotColor anywhere in the face", "spotColor" in face)
        for (branch in listOf("AddButtonArt.CIRCLE ->", "AddButtonArt.DIAMOND ->")) {
            assertTrue("$branch: the neutral elevation", ".shadow(AddButtonShadow.ELEVATION.dp, " in faceBranch(face, branch))
        }
        assertFalse("the bolt keeps no shadow", ".shadow(" in faceBranch(face, "AddButtonArt.BOLT ->"))
    }

    /** D-219 (L14): the Vardr Labs bolt carries the reticle (two paths: the arms stroked, the dot filled); circle and
     *  diamond carry Lucide's plus at 28. */
    @Test
    fun the_bolt_carries_the_reticle() {
        val paths = ArtIcons.Reticle.root.filterIsInstance<androidx.compose.ui.graphics.vector.VectorPath>()
        assertEquals("two paths and nothing else", 2, ArtIcons.Reticle.root.asSequence().count())
        assertEquals(2, paths.size)
        val (arms, dot) = paths
        assertEquals(androidx.compose.ui.graphics.vector.PathParser().parsePathString(ReticleGlyph.ARMS).toNodes(), arms.pathData)
        assertEquals(LucideStroke.WIDTH, arms.strokeLineWidth, 0f)
        assertEquals(androidx.compose.ui.graphics.StrokeCap.Round, arms.strokeLineCap)
        assertEquals(androidx.compose.ui.graphics.StrokeJoin.Round, arms.strokeLineJoin)
        assertNull("the arms are not filled", arms.fill)
        assertTrue("the arms are stroked", arms.stroke != null)
        assertEquals(androidx.compose.ui.graphics.vector.PathParser().parsePathString(ReticleGlyph.DOT).toNodes(), dot.pathData)
        assertTrue("the dot is filled", dot.fill != null)
        assertNull("the dot has no stroke", dot.stroke)
        assertEquals("24 x 24 viewport", 24f, ArtIcons.Reticle.viewportWidth, 0f)
        assertEquals(24f, ArtIcons.Reticle.viewportHeight, 0f)
        val face = faceSource()
        val bolt = faceBranch(face, "AddButtonArt.BOLT ->")
        assertTrue("the bolt draws the reticle", "ArtIcons.Reticle" in bolt)
        assertTrue("at the reticleGlyph size", "ThemeArt.m(\"reticleGlyph\").dp" in bolt)
        assertFalse("the bolt draws no plus", "TabIcons.Plus" in bolt || "plus()" in bolt)
        for (branch in listOf("AddButtonArt.CIRCLE ->", "AddButtonArt.DIAMOND ->")) {
            val body = faceBranch(face, branch)
            assertTrue("$branch draws the plus", "plus()" in body)
            assertFalse("$branch draws no reticle", "ArtIcons.Reticle" in body)
        }
        assertTrue("the plus is Lucide's at 28", "TabIcons.Plus" in face && "Modifier.size(28.dp)" in face)
    }

    /** D-220 (L-D217-3): Android's bar glass. A blur under iOS's 0.72 film where RenderEffect exists (API 31+), C1's 0.94
     *  film where it does not; Norse and Vardr Labs are the solid surface whatever the API level. */
    @Test
    fun the_bar_glass_values() {
        assertEquals(0.72f, TabBarGround.BLUR_FILM_ALPHA, 0f)
        assertEquals(20f, TabBarGround.BLUR_RADIUS, 0f)
        assertFalse(TabBarGround.blurs(26))
        assertFalse(TabBarGround.blurs(30))
        assertTrue(TabBarGround.blurs(31))
        assertTrue(TabBarGround.blurs(37))
        assertEquals(0.72f, TabBarGround.film(AppTheme.AURORA, true), 0f)
        assertEquals(TabBarGround.FILM_ALPHA, TabBarGround.film(AppTheme.AURORA, false), 0f)
        assertEquals(0.94f, TabBarGround.film(AppTheme.AURORA, false), 0f)
        for (blurs in listOf(true, false)) {
            assertEquals("Norse, blurs $blurs", 1f, TabBarGround.film(AppTheme.NORSE, blurs), 0f)
            assertEquals("Vardr Labs, blurs $blurs", 1f, TabBarGround.film(AppTheme.VARDR, blurs), 0f)
        }
    }

    /** D-220: the film over the blur is iOS's own constant, read from ThemeArt.swift (it cannot pass on an empty parse). */
    @Test
    fun the_blur_film_matches_ios() {
        val match = Regex("""static let filmAlpha: (?:Double|CGFloat) = ([0-9.]+)""").find(swift)
        assertTrue("iOS's filmAlpha line is found", match != null)
        assertEquals(match!!.groupValues[1].toFloat(), TabBarGround.BLUR_FILM_ALPHA, 0f)
    }

    /** D-219 (L13, L15): the bar's ground, hairline and pill are the decided ones, read from the source (the look is
     *  checked by eye). */
    @Test
    fun the_tab_bar_draws_the_decided_ground_and_pill() {
        val bar = src("TabBar.kt")
        assertTrue("the ground decision", "TabBarGround.of(" in bar)
        assertTrue("the active glyph decision", "TabIndicator.activeGlyph(" in bar)
        // D-220: the film is reached through the decision (0.72 over the blur, 0.94 where it cannot blur, 1 when solid).
        assertTrue("the film is the decision", "Tok.surface(dark).copy(alpha = TabBarGround.film(theme, blur))" in bar)
        assertFalse("FILM_ALPHA only through film(", "TabBarGround.FILM_ALPHA" in bar)
        assertFalse("no film literal", "copy(alpha = 0.94f)" in bar)
        assertEquals("the hairline is drawn once", 1, Regex("""Tok\.hairline\(dark\)""").findAll(bar).count())
        val chainStart = bar.indexOf("Modifier\n            .fillMaxWidth()")
        val chainEnd = bar.indexOf("verticalAlignment = Alignment.Bottom")
        assertTrue("the bar's modifier chain starts where the test expects", chainStart >= 0)
        assertTrue("the bar's modifier chain ends where the test expects", chainEnd > chainStart)
        val chain = bar.substring(chainStart, chainEnd)
        val line = chain.indexOf("Tok.hairline(dark)")
        val around = chain.substring(maxOf(0, line - 120), minOf(chain.length, line + 160))
        assertTrue("the hairline is in a drawBehind", line > chain.indexOf(".drawBehind"))
        assertTrue("at the bar's top, 1 dp, full width", "topLeft = Offset(0f, 0f)" in around && "Size(size.width, 1.dp.toPx())" in around)
        assertTrue("drawn before navigationBarsPadding, so it covers the system bar's inset too", line < chain.indexOf(".navigationBarsPadding()"))
        assertTrue("the ground is before the hairline", chain.indexOf("backdropGlass(") in 0 until line)
        assertTrue("the pill is filled accent when on", "if (on) Tok.accent(dark) else Color.Transparent, RoundedCornerShape(50)" in bar)
        assertFalse("no accentSoft pill", "accentSoft" in bar)
        assertFalse("glyphUsesRing is gone", "glyphUsesRing" in bar || "glyphUsesRing" in src("ThemeArt.kt"))
        assertTrue("the + box is the footprint tall", ".height(AddButtonGeometry.FOOTPRINT.dp)" in bar)
        assertTrue("the + is lifted by the constant", "-AddButtonGeometry.LIFT" in bar)
        assertFalse("no literal lift", "(-18).dp" in bar)
        // D-219 fix wave (I-1): the + slot is in front of its neighbours, so a face that overflows its footprint wins the taps.
        assertEquals("the + slot is in front of the neighbour tabs", 1, Regex("""Modifier\.weight\(1f\)\.zIndex\(1f\)\.height\(AddButtonGeometry\.FOOTPRINT\.dp\)""").findAll(bar).count())
    }

    /** D-219 (L14): the reticle's two strings are the Look Book's drawing, and are iOS's, byte for byte (read from
     *  ThemeArt.swift; a missing line fails). */
    @Test
    fun the_reticle_matches_ios() {
        assertEquals("M3.5 12h5.5M15 12h5.5M12 3.5V9M12 15v5.5", ReticleGlyph.ARMS)
        assertEquals("M10.7 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0", ReticleGlyph.DOT)
        val arms = Regex("""static let arms = "([^"]+)"""").find(swift)
        val dot = Regex("""static let dot = "([^"]+)"""").find(swift)
        assertTrue("iOS ReticleGlyph.arms parsed", arms != null)
        assertTrue("iOS ReticleGlyph.dot parsed", dot != null)
        assertEquals(arms!!.groupValues[1], ReticleGlyph.ARMS)
        assertEquals(dot!!.groupValues[1], ReticleGlyph.DOT)
    }

    private fun src(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** The shared components read the per-theme decisions. */
    @Test
    fun the_shared_components_read_the_decisions() {
        val bar = src("TabBar.kt")
        assertTrue("tab bar: active mark", "TabIndicator.of(" in bar)
        assertTrue("tab bar: rune icons", "TabIndicator.drawsRunes(" in bar)
        assertTrue("tab bar: the +", "AddButtonFace(" in bar)
        val aurora = src("Aurora.kt")
        assertTrue("glassCard", "CardStyle.of(" in aurora)
        assertTrue("auroraBackground (D-214: pageArt draws the ground)", "Ground.of(" in src("ThemeMotion.kt"))
        assertTrue("buttons", "ButtonLook.of(" in src("GroupScreens.kt"))
        assertTrue("PictureTile", "TileShape.of(" in src("GroupsTab.kt"))
        assertTrue("PersonAvatar", "TileShape.of(" in src("RemovalFlow.kt"))
        assertTrue("AvatarView", "AvatarStyle." in src("Profile.kt"))
    }

    /** No primary action is drawn as a raw accent-filled Button (they would miss Vardr Labs' outline). */
    @Test
    fun no_raw_accent_filled_buttons_remain() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") }!!.filter { f ->
            Regex("""buttonColors\(\s*containerColor\s*=\s*(tint \?: )?Tok\.accent\(dark\)""").containsMatchIn(f.readText()) && f.name != "GroupScreens.kt"
        }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    /** D-213 fix wave 2 (G3): no raw Material `OutlinedButton` outside GroupScreens.kt (PrimaryAction's own outlined
     *  branch), so Vardr Labs' plain words and the other themes' tonal look reach every secondary action. */
    @Test
    fun no_raw_outlined_buttons_remain() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") }!!.filter { f ->
            f.name != "GroupScreens.kt" && Regex("""\bOutlinedButton\b""").containsMatchIn(f.readText())
        }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
        assertTrue("PrimaryAction keeps its own outlined branch", "OutlinedButton(" in src("GroupScreens.kt"))
    }

    /** D-213 fix wave 2 (G2): a person's initial on a tone goes through PersonAvatar (it follows the theme's person
     *  shape); a tone-filled CircleShape initial stays round beside Norse's diamonds. */
    @Test
    fun no_tone_filled_circle_initial_remains() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") }!!.filter { f ->
            Regex("""\.background\(\s*\w*tone\w*\.fill\([^)]*\)\s*,\s*(androidx\.compose\.foundation\.shape\.)?CircleShape""").containsMatchIn(f.readText())
        }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
        assertTrue("Balance's People rows", "PersonAvatar(p.name, dark, 40)" in src("OverviewTab.kt"))
        assertTrue("Settle up cards", "PersonAvatar(other, dark, 44)" in src("SettleAll.kt"))
    }

    /** D-213 fix wave 2 (G1): glassCard's tint is a wash on the card's own shape, drawn after the fill and BEFORE
     *  the edge, hairline and frame marks, in all three styles; the group header passes it instead of an inset panel. */
    @Test
    fun the_tint_is_drawn_under_the_cards_strokes_in_every_style() {
        val aurora = src("Aurora.kt")
        val body = aurora.substring(aurora.indexOf("fun Modifier.glassCard("))
        val branches = body.split("CardStyle.NOTCHED ->", "CardStyle.FRAMED ->")
        assertEquals(3, branches.size)
        val tint = ".background(tint ?: Color.Transparent"
        for ((i, branch) in branches.withIndex()) {
            val wash = branch.indexOf(tint)
            // D-226: Norse's edge is the notch edge (the straight sides), the others a border.
            val stroke = branch.indexOf(if (i == 1) ".notchEdge(" else ".border(")
            assertTrue("style $i draws the tint", wash >= 0)
            assertTrue("style $i: the tint comes before the border", stroke >= 0 && wash < stroke)
            assertEquals("style $i: one tint", wash, branch.lastIndexOf(tint))
        }
        // The framed card's marks are drawn after the tint too.
        val framed = branches[2]
        assertTrue(framed.indexOf(tint) < framed.indexOf("frameMarks("))
        // D-221 (Q16 a): the group header that passed its tone as the tint is bare now (SettledTest.the_group_header_is_bare);
        // no inset tone panel came back in its place.
        val header = src("OverviewTab.kt")
        assertFalse("no inset tone panel", ".background(tone.fill(dark).copy(alpha = 0.55f), RoundedCornerShape" in header)
    }

    /** D-215: the phone's runic fallback is gone, so is the proof that asked it (`--rune-proof`, D-213): the bundled
     *  font is proved on the JVM by [every_rune_is_in_the_bundled_rune_font]. */
    @Test
    fun the_rune_proof_is_retired() {
        assertFalse("RuneProof()" in src("AppRoot.kt"))
        assertFalse("\"rune-proof\"" in src("AppRoot.kt"))
        assertFalse("hasGlyph" in src("ThemeArt.kt"))
    }

    @Test
    fun a_section_label_carries_the_mark_in_norse_only() {
        val norse = SectionLabels.annotated("Theme", AppTheme.NORSE)
        assertEquals("\u16DF Theme", norse.text)
        // The mark and its space are accented; the rune character alone is drawn in the rune font.
        assertEquals(listOf("\u16DF "), coloured(norse).map { norse.text.substring(it.start, it.end) })
        assertEquals(Tok.accent.norse, coloured(norse).single().item.color)
        assertEquals(listOf("\u16DF"), runeSpans(norse).map { norse.text.substring(it.start, it.end) })
        assertEquals("Theme", SectionLabels.annotated("Theme", AppTheme.AURORA).text)
        assertEquals("THEME", SectionLabels.annotated("Theme", AppTheme.VARDR).text)
        assertTrue(SectionLabels.annotated("Theme", AppTheme.AURORA).spanStyles.isEmpty())
        assertTrue(SectionLabels.annotated("Theme", AppTheme.VARDR).spanStyles.isEmpty())
        // A rune inside the words (a currency heading with a separator) is in the rune font and NOT accented.
        val heading = SectionLabels.annotated("Settle in INR \u16EB Goa", AppTheme.NORSE)
        assertEquals(listOf("\u16DF", "\u16EB"), runeSpans(heading).map { heading.text.substring(it.start, it.end) })
        assertEquals(listOf("\u16DF "), coloured(heading).map { heading.text.substring(it.start, it.end) })
    }

    @Test
    fun titles_take_the_themes_display_face() {
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            val t = Type.title(dark = true, size = TypeScale.inlineTitle)
            assertEquals("$theme family", Type.display(true).fontFamily, t.fontFamily)
            assertEquals("$theme weight", if (theme == AppTheme.VARDR) 600 else 700, t.fontWeight?.weight)
            // D-217: the size is the caller's (the bar's), not a default.
            assertEquals(TypeScale.inlineTitle, t.fontSize.value, 0f)
            assertEquals("$theme tracking", Type.titleTracking(theme), t.letterSpacing)
        }
        assertEquals(-0.01f, Type.titleTracking(AppTheme.AURORA).value, 0.0001f)
        assertEquals(0.03f, Type.titleTracking(AppTheme.NORSE).value, 0.0001f)
        assertEquals(-0.02f, Type.titleTracking(AppTheme.VARDR).value, 0.0001f)
    }

    @Test
    fun the_ornament_is_norses_alone_and_sits_on_the_four_roots() {
        assertEquals(listOf(false, true, false), AppTheme.entries.map(TitleOrnaments::shows))
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val users = dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "ThemeArt.kt" && f.name != "SkiptiBar.kt" }!!
            .filter { "TitleOrnament(" in it.readText() }.map { it.name }.sorted()
        // D-217 Task 6: the ornament is drawn by TabRootScaffold (SkiptiBar.kt) alone, under the large title, for
        // `ornament = true`; no screen draws one of its own (they all did before the scaffold).
        // D-245 (U9, the approved mock-up v2): the welcome's centred titles carry it in Norse too (a full-screen layer
        // with its own titles, not a pushed page); iOS's twin amended alike.
        assertEquals("no screen but the welcome draws an ornament", listOf("Welcome.kt"), users)
        assertTrue(
            "TabRootScaffold draws the ornament",
            "if (ornament) TitleOrnament(dark, above = BarDecisions.CONTENT_SPACING, belowBaseline = " in src("SkiptiBar.kt"),
        )
        // ...and it is the only call there: a second site in the bar file would not be one of the four roots.
        assertEquals("one ornament site in SkiptiBar.kt", 1, src("SkiptiBar.kt").split("TitleOrnament(").size - 1)
        // D-217: You is a pushed page (an inline title on iOS), so it has no ornament; the four tab roots do: each
        // is a TabRootScaffold with `ornament = true`, and nothing else in the app calls the scaffold with it.
        val callers = dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "SkiptiBar.kt" }!!
            .filter { "TabRootScaffold(" in it.readText() }.map { it.name }.sorted()
        assertEquals(listOf("ExpenseListScreen.kt", "GroupsTab.kt", "OverviewTab.kt", "SettleAll.kt"), callers)
        for (f in callers) {
            val call = src(f).substringAfter("TabRootScaffold(").substringBefore(") {")
            assertTrue("$f passes ornament = true", "ornament = true" in call)
        }
        assertFalse("You has no ornament", "TabRootScaffold(" in src("AccountHubScreen.kt"))
    }

    /** D-217 (the Look Book's `.orn`; twin of iOS's testTheOrnamentsRulesAreFortyTwoPercent...): each rule is 42% of the
     *  width, the rune takes the 16% between them, and the ornament hangs 8 dp under the title; the scaffold says how many
     *  dp its column already puts over the ornament, and how far the title's box ends below its baseline: the offset
     *  cancels both. */
    @Test
    fun the_ornaments_rules_are_forty_two_percent_and_it_hangs_eight_dp_under_the_title() {
        assertEquals(0.42f, TitleOrnaments.RULE_FRACTION, 0f)
        assertEquals(150.36f, TitleOrnaments.ruleWidth(358f), 0.001f)
        assertEquals(358f * 0.16f, 358f - 2 * TitleOrnaments.ruleWidth(358f), 0.001f)
        assertEquals(8, TitleOrnaments.GAP_UNDER_TITLE)
        assertEquals("D-226: holds the 17 dp rune (16 for 13 before)", 20, TitleOrnaments.ROW_HEIGHT)
        assertEquals(-6, TitleOrnaments.topOffset(above = 14))
        assertEquals(-4, TitleOrnaments.topOffset(above = 12))
        assertEquals(0, TitleOrnaments.topOffset(above = 8))
        // The columns' spacing is the scaffold's one constant, so no root can put a different number over its ornament.
        assertEquals(14, BarDecisions.CONTENT_SPACING)
        assertEquals(-16, TitleOrnaments.topOffset(above = 14, belowBaseline = 10))
        assertTrue("the rules take the fractions", "Modifier.weight(TitleOrnaments.RULE_FRACTION)" in src("ThemeArt.kt"))
        assertTrue("the rune's gap is the rest", "Modifier.weight(1f - 2 * TitleOrnaments.RULE_FRACTION)" in src("ThemeArt.kt"))
    }

    /** D-217: the tile's initial is the display face at 700 in every theme (the Look Book's `.tile`), 0.42 of the tile
     *  (D-220: on the diamond too: it is drawn at size x sqrt 2 now, so one factor). */
    @Test
    fun the_tiles_initial_is_the_display_face_at_700() {
        for (theme in AppTheme.entries) {
            Appearance.setForTest(theme = theme)
            for (shape in TileShape.entries) {
                val style = tileInitialStyle(shape, 40, false, androidx.compose.ui.graphics.Color.Red)
                assertEquals("$theme $shape face", Type.display(false).fontFamily, style.fontFamily)
                assertEquals("$theme $shape weight", 700, style.fontWeight!!.weight)
                assertEquals("$theme $shape size", 16.8f, style.fontSize.value, 0.001f)
            }
        }
    }

    /** D-217: the two chevrons are Lucide's own bytes, the same strings as iOS's `LucideGlyph` (read from the Swift
     *  file), and a list row's chevron is `RowChevron` at every site; the "›" text glyph is gone everywhere. */
    @Test
    fun the_chevrons_are_lucides_and_the_same_on_both_shells() {
        val lucide = java.io.File("../../ios/Skipti/App/LucideIcon.swift").readText()
        fun swiftPath(case: String): List<String> {
            val body = lucide.substringAfter("case .$case:").substringBefore("case .").substringBefore("        }\n")
            return Regex("\"([^\"]+)\"").findAll(body.substringAfter("return [").substringBefore("]")).map { it.groupValues[1] }.toList()
        }
        assertEquals(listOf("m15 18-6-6 6-6"), swiftPath("chevronLeft"))
        assertEquals(listOf("m9 18 6-6-6-6"), swiftPath("chevronRight"))
        assertEquals(swiftPath("chevronLeft"), TabIcons.PATHS.getValue("chevron-left"))
        assertEquals(swiftPath("chevronRight"), TabIcons.PATHS.getValue("chevron-right"))
        assertEquals(24f, TabIcons.ChevronLeft.viewportWidth, 0f)
        assertEquals(24f, TabIcons.ChevronRight.viewportHeight, 0f)
        assertEquals("iOS's RowChevron.size", 20, RowChevronSize)
        assertTrue("iOS draws RowChevron at the same size", "static let size: CGFloat = $RowChevronSize" in lucide)
        for (f in mainSources()) assertFalse("${f.name} draws a \u203A text chevron", "\"\u203A\"" in f.readText())
        for ((file, count) in listOf(
            // D-225: You's chevron is the shared ListRow's (a chevron row and a value row with its chevron).
            // D-225 T5: ListRows.kt 3 (the drawn piece's chevron, Group settings' picture), About.kt and GroupSettings.kt 0 (their
            // rows are list rows); GroupScreens.kt's one is People's member row. D-234 (A2): ListRows.kt 4, the disclosure's.
            "OverviewTab.kt" to 1, "AccountHubScreen.kt" to 0, "ListRows.kt" to 4, "About.kt" to 0, "GroupSettings.kt" to 0, "GroupScreens.kt" to 1, "RemovalFlow.kt" to 1,
        )) assertEquals("$file: RowChevron", count, src(file).split("RowChevron(").size - 1)
    }

    @Test
    fun no_middle_dot_join_is_left_in_app_ui() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val offenders = mutableListOf<String>()
        dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "Push.kt" && f.name != "ThemeArt.kt" }!!.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (!code.startsWith("//") && !code.startsWith("*") && !code.startsWith("/**") && " \u00B7 " in code) offenders += "${f.name}:${i + 1}"
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The counts the classification produces (the rule: a section label names what follows; status words,
     *  hints, warnings, proof results, row flags and person or debt labels keep the plain kicker). */
    @Test
    fun section_labels_carry_the_mark_and_the_rest_do_not() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val all = dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "ThemeArt.kt" && f.name != "Tokens.kt" }!!.joinToString("\n") { it.readText() }
        assertEquals(SECTION_LABELS, Regex("""\bSectionLabel\(""").findAll(all).count())
        assertEquals(KICKERS, Regex("""Type\.kicker\(""").findAll(all).count())
    }

    /** F1: the raised + takes its click AFTER its shape's clip in every theme (the circle's and the diamond's
     *  `shadow`, the bolt's `clip(HexagonShape)`), so the ripple and the touch area follow the shape; the tab
     *  bar no longer hangs a `clickable` on the face from outside. The ripple itself is checked by eye. */
    @Test
    fun the_raised_plus_takes_its_click_after_its_shapes_clip() {
        val art = src("ThemeArt.kt")
        val start = art.indexOf("fun AddButtonFace(")
        val face = art.substring(start, art.indexOf("\n}\n", start))
        assertTrue("the face owns the click", ".clickable(" in face && "onClickLabel = label" in face && "contentDescription = label" in face)
        val branches = listOf("AddButtonArt.CIRCLE ->" to ".shadow(", "AddButtonArt.DIAMOND ->" to ".shadow(", "AddButtonArt.BOLT ->" to ".clip(HexagonShape)")
        for ((i, pair) in branches.withIndex()) {
            val (branch, clip) = pair
            val from = face.indexOf(branch)
            val to = branches.getOrNull(i + 1)?.let { face.indexOf(it.first) } ?: face.length
            assertTrue("$branch present", from >= 0 && to > from)
            val body = face.substring(from, to)
            assertEquals("$branch: one click", 1, Regex("""\.then\(click\)""").findAll(body).count())
            assertTrue("$branch: $clip before the click", body.indexOf(clip) in 0 until body.indexOf(".then(click)"))
        }
        val bar = src("TabBar.kt")
        val call = bar.substring(bar.indexOf("AddButtonFace("), bar.indexOf("AddButtonFace(") + 600)
        assertTrue("the tab bar passes the click to the face", "onClick = onAdd" in call)
        assertTrue("TabBar.kt: no clickable outside the face", ".clickable(" !in bar)
    }

    /** F2: a rune drawn as art keeps its dp size at every text size (iOS draws them at fixed sizes): at a
     *  font scale of 2, the 34 dp tab rune is 20 sp (Android's non-linear font scaling: 22 dp was 11 sp), which draws 34 dp. */
    @Test
    fun the_art_runes_keep_their_size_at_every_text_size() {
        val density = androidx.compose.ui.unit.Density(density = 3f, fontScale = 2f)
        assertEquals(20f, runeSize("tabRune", density).value, 0.0001f)
        assertEquals(34f, with(density) { runeSize("tabRune", density).toDp() }.value, 0.0001f)
        assertEquals("D-226: the 17 dp ornament rune (6.5 for 13 before)", 8.5f, runeSize("ornamentRune", density).value, 0.0001f)
        assertEquals(34f, runeSize("tabRune", androidx.compose.ui.unit.Density(density = 3f, fontScale = 1f)).value, 0.0001f)
        assertTrue("tab bar", "Type.rune(runeSize(\"tabRune\", LocalDensity.current))" in src("TabBar.kt"))
        assertTrue("ornament", "Type.rune(runeSize(\"ornamentRune\", LocalDensity.current))" in src("ThemeArt.kt"))
        for (f in listOf("TabBar.kt", "ThemeArt.kt")) {
            assertTrue("$f: a rune in plain sp", !Regex("""m\("(tabRune|ornamentRune)"\)\.sp""").containsMatchIn(src(f)))
        }
    }

    // ---- D-220 Task 1: the pill, meter, card and panel decisions; tiles; blue; disabled; the two Lucide drawings ----

    /** L19: Aurora's pill is a capsule, Norse's notched at 5 (D-226; 4 before), Vardr Labs' square; Aurora and Norse
     *  fill it, Vardr Labs outlines it. Twin of iOS's testThePillShapePerTheme. */
    @Test
    fun the_pill_shape_per_theme() {
        assertEquals(listOf(PillKind.CAPSULE, PillKind.NOTCHED, PillKind.SQUARE), AppTheme.entries.map(ThemeArt::pillKind))
        assertEquals(5f, ThemeArt.pillChamfer, 0f)
        assertEquals(listOf(PillLook.FILLED, PillLook.FILLED, PillLook.OUTLINED), AppTheme.entries.map(PillLook::of))
        assertEquals(RoundedCornerShape(50), ThemeArt.pillShape(AppTheme.AURORA))
        assertTrue(ThemeArt.pillShape(AppTheme.NORSE) is NotchedShape)
        assertTrue(ThemeArt.pillShape(AppTheme.VARDR) === androidx.compose.ui.graphics.RectangleShape)
        // iOS's `pillKind` says the same words, read from its source.
        val kind = swift.substringAfter("static func pillKind(").substringBefore("static func pillShape")
        assertTrue(".aurora: return .capsule" in kind && ".norse: return .notched(m(\"pillChamfer\"))" in kind && ".vardr: return .square" in kind)
    }

    /** The plan's table, row by row; the same rows as iOS's testThePillPaintTable (the shared table is the parity). */
    @Test
    fun the_pill_paint_table() {
        fun t(tok: Tok) = PillInk.Token(tok)
        fun l(ink: PillInk, a: Float = 1f) = PillPaint.Layer(ink, a)
        val o = ThemeArt.m("pillOutline")
        val page = l(t(Tok.bg))
        for (onPage in listOf(false, true)) {
            assertEquals(PillPaint(t(Tok.accent), l(t(Tok.accentSoft)), null), PillPaint.of(PillTone.ACCENT, PillLook.FILLED, onPage))
            assertEquals(PillPaint(PillInk.AmberLetter, l(PillInk.AmberFill), null), PillPaint.of(PillTone.OWNER, PillLook.FILLED, onPage))
            assertEquals(PillPaint(t(Tok.onAccent), l(t(Tok.accent)), null), PillPaint.of(PillTone.SELECTED, PillLook.FILLED, onPage))
            assertEquals(PillPaint(t(Tok.text), l(t(Tok.surface), 0.8f), l(t(Tok.hairline))), PillPaint.of(PillTone.UNSELECTED, PillLook.FILLED, onPage))
            assertEquals(PillPaint(t(Tok.onHero), l(t(Tok.onHero), Aurora.HERO_CHIP_ALPHA), null), PillPaint.of(PillTone.HERO, PillLook.FILLED, onPage))
            assertEquals(PillPaint(PillInk.White, l(PillInk.Black, PhotoCard.CHIP_ALPHA), null), PillPaint.of(PillTone.PHOTO, PillLook.FILLED, onPage))
        }
        assertEquals(0.12f, Aurora.HERO_CHIP_ALPHA, 0f)
        assertEquals(0.55f, PhotoCard.CHIP_ALPHA, 0f)
        assertEquals(PillPaint(t(Tok.accent), null, l(t(Tok.accent), o)), PillPaint.of(PillTone.ACCENT, PillLook.OUTLINED))
        assertEquals(PillPaint(PillInk.AmberLetter, null, l(PillInk.AmberLetter, o)), PillPaint.of(PillTone.OWNER, PillLook.OUTLINED))
        assertEquals(PillPaint(t(Tok.accent), null, l(t(Tok.accent))), PillPaint.of(PillTone.SELECTED, PillLook.OUTLINED))
        assertEquals(PillPaint(t(Tok.text), null, l(t(Tok.hairline))), PillPaint.of(PillTone.UNSELECTED, PillLook.OUTLINED))
        assertEquals(PillPaint(t(Tok.onHero), null, l(t(Tok.onHero), o)), PillPaint.of(PillTone.HERO, PillLook.OUTLINED))
        assertEquals(PillPaint(t(Tok.accent), page, l(t(Tok.accent), o)), PillPaint.of(PillTone.ACCENT, PillLook.OUTLINED, true))
        assertEquals(PillPaint(PillInk.AmberLetter, page, l(PillInk.AmberLetter, o)), PillPaint.of(PillTone.OWNER, PillLook.OUTLINED, true))
        assertEquals(PillPaint(t(Tok.accent), page, l(t(Tok.accent))), PillPaint.of(PillTone.SELECTED, PillLook.OUTLINED, true))
        assertEquals(PillPaint(t(Tok.text), page, l(t(Tok.hairline))), PillPaint.of(PillTone.UNSELECTED, PillLook.OUTLINED, true))
        assertEquals(PillPaint.of(PillTone.HERO, PillLook.OUTLINED), PillPaint.of(PillTone.HERO, PillLook.OUTLINED, true))
        // The negative: a photo pill keeps its film in every look, on the page or not: no `bg` fill, no border.
        val film = PillPaint.of(PillTone.PHOTO, PillLook.FILLED)
        for (onPage in listOf(false, true)) assertEquals(film, PillPaint.of(PillTone.PHOTO, PillLook.OUTLINED, onPage))
        assertEquals(l(PillInk.Black, 0.55f), film.fill)
        assertNull(film.border)
        assertEquals(1f, PillPaint.BORDER_WIDTH, 0f)
        // iOS's table is the same words: its alphas and the one line that gives the outlined photo its film, from source.
        assertTrue("case (.photo, _):" in swift)
        assertTrue("Layer(.token(\"surface\"), 0.8)" in swift)
        // Every ink resolves in every set.
        for (tone in PillTone.entries) for (look in PillLook.entries) for (onPage in listOf(false, true)) {
            val p = PillPaint.of(tone, look, onPage)
            for (ink in listOfNotNull(p.words, p.fill?.ink, p.border?.ink)) for (set in ThemeSet.entries) ink.of(set)
        }
    }

    /** L22: a meter is a capsule in Aurora and square elsewhere; the swatch rounds 2 in Aurora only. */
    @Test
    fun meters_and_swatches_per_theme() {
        assertEquals(listOf(MeterKind.CAPSULE, MeterKind.SQUARE, MeterKind.SQUARE), AppTheme.entries.map(ThemeArt::meterKind))
        assertEquals(listOf(2, 0, 0), AppTheme.entries.map(ThemeArt::swatchRadius))
        assertEquals(RoundedCornerShape(50), ThemeArt.meterShape(AppTheme.AURORA))
        assertTrue(ThemeArt.meterShape(AppTheme.VARDR) === androidx.compose.ui.graphics.RectangleShape)
        assertTrue(ThemeArt.meterShape(AppTheme.NORSE) === androidx.compose.ui.graphics.RectangleShape)
        assertEquals(RoundedCornerShape(2.dp), ThemeArt.swatchShape(AppTheme.AURORA))
        assertEquals(RoundedCornerShape(0.dp), ThemeArt.swatchShape(AppTheme.VARDR))
    }

    /** L20: panels are square in Vardr Labs; Aurora keeps the asked radius; Norse keeps its notches. */
    @Test
    fun panels_are_square_in_vardr_labs() {
        assertEquals(ControlShapeKind.Rounded(0), ThemeArt.panelKind(AppTheme.VARDR, 14, 60f))
        assertEquals(ControlShapeKind.Rounded(14), ThemeArt.panelKind(AppTheme.AURORA, 14, 60f))
        assertEquals("D-226: 10 (8 before)", ControlShapeKind.Notched(10f), ThemeArt.panelKind(AppTheme.NORSE, 14, 60f))
        assertEquals("D-226: 8 (6 before)", ControlShapeKind.Notched(8f), ThemeArt.panelKind(AppTheme.NORSE, 14, 30f))
        assertEquals(RoundedCornerShape(0.dp), ThemeArt.panelShape(AppTheme.VARDR, 14))
        assertEquals(RoundedCornerShape(14.dp), ThemeArt.panelShape(AppTheme.AURORA, 14))
    }

    /** L21: the card's shape by kind, as iOS's testTheCardShapePerTheme. */
    @Test
    fun the_card_shape_per_theme() {
        assertEquals(CardShapeKind.Rounded(20), ThemeArt.cardKind(AppTheme.AURORA))
        assertEquals(CardShapeKind.Rounded(18), ThemeArt.cardKind(AppTheme.AURORA, 18))
        assertEquals("D-226: 10 (8 before)", CardShapeKind.Notched(10f), ThemeArt.cardKind(AppTheme.NORSE))
        assertEquals(CardShapeKind.Notched(10f), ThemeArt.cardKind(AppTheme.NORSE, 18))
        assertEquals(CardShapeKind.Square, ThemeArt.cardKind(AppTheme.VARDR))
        assertEquals(CardShapeKind.Square, ThemeArt.cardKind(AppTheme.VARDR, 18))
        assertEquals(RoundedCornerShape(18.dp), ThemeArt.cardShape(AppTheme.AURORA, 18))
        assertTrue(ThemeArt.cardShape(AppTheme.NORSE) is NotchedShape)
        assertTrue(ThemeArt.cardShape(AppTheme.VARDR) === androidx.compose.ui.graphics.RectangleShape)
    }

    /** L23: the diamond is drawn at size x sqrt 2 (art.py section 15: 56 x sqrt 2 = 79.196). */
    @Test
    fun the_norse_diamond_is_the_tiles_size() {
        assertEquals(79.196f, tileDrawnSide(TileShape.DIAMOND, 56), 0.01f)
        assertEquals(42.426f, tileDrawnSide(TileShape.DIAMOND, 30), 0.01f)
        for (shape in listOf(TileShape.ROUNDED, TileShape.CIRCLE, TileShape.SQUARE)) assertEquals(56f, tileDrawnSide(shape, 56), 0f)
        val art = src("ThemeArt.kt")
        assertEquals("one factor", 1, art.split("size * 0.42f").size - 1)
        assertEquals("the diamond's smaller letter is gone", 0, art.split("size * 0.36f").size - 1)
    }

    /** D-222 A2 (D-221 re-review I-1): Norse's tile edge is a 1 dp stroke CENTRED on the diamond, as iOS's
     *  `Diamond(radius:).stroke(ThemeArt.norseEdge, lineWidth: 1)` draws it (half outside), not a `border` inside the
     *  shape, which drew Android's 48 dp group tile 65.9 across against iOS's 67.7. The extent is derived here from the
     *  quadratic corner, as D-219's + diamond fix did. */
    @Test
    fun the_tile_edge_is_centred_like_ios() {
        val side = tileDrawnSide(TileShape.DIAMOND, 48)
        val v = ArtGeometry.diamond(side, side)[0]
        val next = ArtGeometry.diamond(side, side)[1]
        val len = kotlin.math.hypot(next.x - v.x, next.y - v.y)
        val ay = v.y + (next.y - v.y) * ThemeArt.m("tileRadius") / len
        val apexY = 0.25f * ay + 0.5f * v.y + 0.25f * ay
        // D-226: tileRadius 3 (2 before: apex 0.707, path 66.47, 67.47 across against iOS's measured 67.7); the new
        // numbers are .drafts/d226/art.py section 46's.
        assertEquals(1.061f, apexY, 0.01f)
        val path = side - 2 * apexY
        assertEquals(65.76f, path, 0.01f)
        // Centred: half the 1 dp edge lies outside each tip, 66.76 across.
        assertEquals(66.76f, path + TileEdge.WIDTH, 0.01f)
        val ios = Regex("""Diamond\(radius: ThemeArt\.m\("tileRadius"\)\)\.stroke\(ThemeArt\.norseEdge, lineWidth: ([0-9.]+)\)""").find(swift)
        assertTrue("iOS's tile edge parsed (a stroke, not a strokeBorder)", ios != null)
        assertEquals(ios!!.groupValues[1].toFloat(), TileEdge.WIDTH, 0f)
        // Source guard: drawn over the content as a centred stroke, never an inside border.
        val art = src("ThemeArt.kt")
        val body = art.substringAfter("fun Modifier.tileEdge(").substringBefore("\n\n")
        assertTrue("drawn over the content", "drawWithContent" in body && body.indexOf("drawContent()") in 0 until body.indexOf("drawPath("))
        assertTrue("a centred stroke of the edge width", "style = Stroke(width = TileEdge.WIDTH.dp.toPx())" in body)
        assertFalse("no inside border", "border(" in body)
        // Every caller lays the edge before any clip of its chain: a clip before it would cut the outer half away.
        var callers = 0
        for (file in java.io.File("src/main/java/com/vardrlabs/skipti").listFiles()!!.filter { it.name.endsWith(".kt") }) {
            val text = file.readText()
            var at = text.indexOf(".tileEdge(")
            while (at >= 0) {
                if (!text.startsWith("fun Modifier.tileEdge(", at - "fun Modifier".length)) {
                    val chain = text.substring(text.lastIndexOf("Modifier", at), at)
                    assertFalse("${file.name}: no clip before the edge", ".clip(" in chain)
                    callers++
                }
                at = text.indexOf(".tileEdge(", at + 1)
            }
        }
        // D-245: + the welcome's five places (W1), each in the theme's person tile.
        assertEquals("the five tiles (group header, milestone seal, removal person, D-222 You's settings tile, D-245 W1's places)", 5, callers)
    }

    /** iOS's `static let disabledAlpha: Double = 0.45` (read from ThemeArt.swift) is Android's constant. */
    @Test
    fun disabled_alpha_matches_ios() {
        val m = Regex("""static let disabledAlpha: Double = ([0-9.]+)""").find(swift)
        assertTrue("iOS ButtonLook.disabledAlpha parsed", m != null)
        assertEquals(m!!.groupValues[1].toFloat(), ButtonLook.DISABLED_ALPHA, 0f)
        assertEquals(0.45f, ButtonLook.DISABLED_ALPHA, 0f)
    }

    /** C2's search-and-check parity, widened in D-222: EVERY drawing of iOS's `LucideGlyph` (each `case .x:` block of its
     *  `pathData`, the string literals up to the next case, mapped to the case's raw name) is Android's [TabIcons.PATHS]
     *  entry of that name, and the two maps have the same names: a missing or extra drawing fails. */
    @Test
    fun every_lucide_path_matches_ios() {
        val lucide = java.io.File("../../ios/Skipti/App/LucideIcon.swift").readText()
        val enumBody = lucide.substringAfter("enum LucideGlyph: String, CaseIterable {").substringBefore("var pathData: [String] {")
        val raw = Regex("""(?m)^    case (\w+)(?: = "([^"]+)")?$""").findAll(enumBody)
            .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[1] } }
        assertTrue("iOS's cases parsed", raw.size >= 16)
        val switchBody = lucide.substringAfter("var pathData: [String] {").substringBefore("func path() -> Path {")
            .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        val blocks = Regex("""case \.(\w+):""").findAll(switchBody).toList()
        val ios = blocks.mapIndexed { i, m ->
            val end = blocks.getOrNull(i + 1)?.range?.first ?: switchBody.length
            val name = raw[m.groupValues[1]] ?: error("case .${m.groupValues[1]} is not a LucideGlyph case")
            name to Regex("\"([^\"]+)\"").findAll(switchBody.substring(m.range.last + 1, end)).map { it.groupValues[1] }.toList()
        }.toMap()
        assertEquals("every case has its drawing", raw.values.toSet(), ios.keys)
        assertEquals(ios, TabIcons.PATHS)
        assertEquals(listOf("m21 21-4.34-4.34", "M11 3a8 8 0 1 0 0 16 8 8 0 1 0 0-16"), ios["search"])
        assertEquals(listOf("M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5"), ios["download"])
        // D-225: the cross and the lock, upstream's bytes (lucide-static v1.59.0), on iOS as on Android.
        assertEquals(listOf("M18 6 6 18", "m6 6 12 12"), ios["x"])
        assertEquals(
            listOf("M5 11 L19 11 A2 2 0 0 1 21 13 L21 20 A2 2 0 0 1 19 22 L5 22 A2 2 0 0 1 3 20 L3 13 A2 2 0 0 1 5 11 Z", "M7 11V7a5 5 0 0 1 10 0v4"),
            ios["lock"],
        )
        // D-231: the Less motion row's sparkles, upstream's bytes (lucide-static v1.59.0), its circle as the two-arc path.
        assertEquals(
            listOf(
                "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
                "M20 2v4", "M22 4h-4", "M4 18a2 2 0 1 0 0 4 2 2 0 1 0 0-4",
            ),
            ios["sparkles"],
        )
        for (icon in listOf(TabIcons.Search, TabIcons.Check, TabIcons.Sun, TabIcons.Shield, TabIcons.Download, TabIcons.Info, TabIcons.X, TabIcons.Lock, TabIcons.Sparkles)) {
            assertEquals(24f, icon.viewportWidth, 0f)
        }
    }

    /** G1 fix 1: Norse's inner panels and stat tiles take the cards' notch. ONE rule sets the cut: 10 on a panel
     *  taller than 50 dp, else 8 (D-226, art.py section 46: both scaled with the chamfer; 8 above 40, else 6, before);
     *  the geometry clamps it to half the short side. Aurora and Vardr Labs keep their radius. */
    @Test
    fun the_panel_chamfer_rule() {
        assertEquals(10f, ThemeArt.panelChamfer(51f), 0f)
        assertEquals(8f, ThemeArt.panelChamfer(50f), 0f)
        assertEquals(8f, ThemeArt.panelChamfer(20f), 0f)
        assertEquals(ThemeArt.m("chamfer"), ThemeArt.panelChamfer(120f), 0f)
        assertEquals(ControlShapeKind.Notched(10f), ThemeArt.panelKind(AppTheme.NORSE, 12, 52f))
        assertEquals(ControlShapeKind.Notched(8f), ThemeArt.panelKind(AppTheme.NORSE, 12, 40f))
        assertEquals(ControlShapeKind.Rounded(12), ThemeArt.panelKind(AppTheme.AURORA, 12, 52f))
        assertEquals("D-220 (L20): square in Vardr Labs", ControlShapeKind.Rounded(0), ThemeArt.panelKind(AppTheme.VARDR, 18, 20f))
        // The drawn shape reads the rule at the panel's own height in dp: 90 px at density 3 is 30 dp (8), 180 px is 60 dp (10).
        val density = androidx.compose.ui.unit.Density(density = 3f, fontScale = 1f)
        assertEquals(8f, PanelShape.chamferDp(90f, density), 0f)
        assertEquals(10f, PanelShape.chamferDp(180f, density), 0f)
        assertTrue(ThemeArt.panelShape(AppTheme.NORSE, 12) === PanelShape)
        assertEquals(RoundedCornerShape(12.dp), ThemeArt.panelShape(AppTheme.AURORA, 12))
        assertEquals(RoundedCornerShape(0.dp), ThemeArt.panelShape(AppTheme.VARDR, 18))
        // The geometry clamps the small chamfer (8) on a 10 dp panel to 5.
        assertEquals(Offset(5f, 0f), ArtGeometry.notched(100f, 10f, ThemeArt.panelChamfer(10f))[0])
        assertEquals(Offset(100f, 5f), ArtGeometry.notched(100f, 10f, ThemeArt.panelChamfer(10f))[2])
        assertTrue("the shape asks the rule", "NotchedShape(chamferDp(size.height, density).dp)" in src("ThemeArt.kt").substringAfter("object PanelShape"))
    }

    /** G1 fix 1: every inner panel and stat tile takes its shape from `panelShape`, so a new one cannot miss it: none
     *  of these files draws a rounded rectangle of 8 to 18 dp of its own, and each names `panelShape` the exact number
     *  of times. (Cards, the photo header's clip, the theme previews, the emoji cells and the pills are not panels.) */
    @Test
    fun every_panel_takes_the_shape_from_the_one_decision() {
        val sites = listOf(
            // D-225 T5: About.kt 0 (its cards and licence panels are glass cards, Design 6).
            // D-225 T7: AddExpenseScreen.kt 0 (an item line is a list card, Design 11).
            "About.kt" to 0, "AddExpenseScreen.kt" to 0, "ExpenseDetailScreen.kt" to 3, "ExpenseListScreen.kt" to 1,
            "GroupScreens.kt" to 0, "GroupSettings.kt" to 2, "RemovalFlow.kt" to 2, "SettleAll.kt" to 1, "SettleScreen.kt" to 1,
            "OverviewTab.kt" to 1,
        )
        val own = Regex("""RoundedCornerShape\((8|10|12|14|16|18)\.dp\)|RoundedCornerShape\(radius\.dp\)""")
        for ((file, count) in sites) {
            val text = src(file)
            assertEquals(file, count, text.split("ThemeArt.panelShape(").size - 1)
            if (file != "OverviewTab.kt") assertTrue("$file: a rounded rectangle of its own", !own.containsMatchIn(text))
        }
        assertTrue("the stat tile", "RoundedCornerShape(14.dp)" !in src("OverviewTab.kt"))
    }

    /** G1 (the founder's look: "the entire tile has so much space"): Norse's tab runes are 34 dp. Vardr Runes Carved
     *  draws its ink in 0.70 em (cap height 700/1000), so the ink stands 23.8 dp inside the 30 dp slot; the line box
     *  (ascent 900 + descent 250 per 1000) is 39.1 dp and overflows the slot, so the slot stays 30 dp (the label, the
     *  underline and the + keep their places) and the rune is centred by its ink: the ink middle sits 350 above the
     *  baseline, the line box middle 325, so it moves down 25/1000 em (0.85 dp). The rune does not scale with text size. */
    @Test
    fun the_tab_runes_fill_their_slot_by_their_ink() {
        assertEquals(34f, ThemeArt.m("tabRune"), 0.0001f)
        assertEquals(23.8f, ThemeArt.m("tabRune") * 0.70f, 0.0001f)
        assertTrue(ThemeArt.m("tabRune") * 0.70f < 30f)
        assertEquals(39.1f, ThemeArt.m("tabRune") * 1.15f, 0.0001f)
        assertEquals(0.025f, ThemeArt.RUNE_INK_SHIFT_EM, 1e-7f)
        assertEquals(0.85f, ThemeArt.tabRuneInkShift, 1e-5f)
        val bar = src("TabBar.kt")
        assertEquals(1, bar.split(".offset(y = ThemeArt.tabRuneInkShift.dp)").size - 1)
        assertEquals("laid out at its own line height", 1, bar.split(".wrapContentHeight(unbounded = true)").size - 1)
        assertEquals("the slot stays 52 x 30", 1, bar.split(".size(width = 52.dp, height = 30.dp)").size - 1)
    }

    /** G1: ONE decision names the shape of every button and input: Norse notches it like its cards (chamfer 10 since
     *  D-226, 8 before); Aurora and Vardr Labs keep the rounded rectangle they had (a call site may name its own shape). */
    @Test
    fun the_control_shape_is_notched_in_norse_only() {
        assertEquals(ControlShapeKind.Notched(10f), ThemeArt.controlKind(AppTheme.NORSE))
        assertEquals(ControlShapeKind.Notched(10f), ThemeArt.controlKind(AppTheme.NORSE, 10))
        assertEquals(ControlShapeKind.Rounded(14), ThemeArt.controlKind(AppTheme.AURORA))
        assertEquals(ControlShapeKind.Rounded(0), ThemeArt.controlKind(AppTheme.VARDR))
        assertEquals(ControlShapeKind.Rounded(10), ThemeArt.controlKind(AppTheme.AURORA, 10))
        assertEquals(ControlShapeKind.Rounded(10), ThemeArt.controlKind(AppTheme.VARDR, 10))
        assertEquals(ControlShapeKind.Notched(ThemeArt.m("chamfer")), ThemeArt.controlKind(AppTheme.NORSE))
        assertTrue(ThemeArt.controlShape(AppTheme.NORSE) is NotchedShape)
        assertEquals(RoundedCornerShape(14.dp), ThemeArt.controlShape(AppTheme.AURORA))
        assertEquals(RoundedCornerShape(0.dp), ThemeArt.controlShape(AppTheme.VARDR))
        // A call site's own shape holds outside Norse (an input's Material default), and never in Norse.
        val own = RoundedCornerShape(4.dp)
        assertEquals(own, ThemeArt.controlShape(AppTheme.AURORA, own))
        assertTrue(ThemeArt.controlShape(AppTheme.NORSE, own) is NotchedShape)
    }

    /** G1: every button and input background asks for the shape from the one decision, so a new button cannot
     *  miss it: no site draws a `buttonRadius` rounded rectangle of its own. */
    @Test
    fun every_control_takes_the_shape_from_the_one_decision() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        for (f in dir.listFiles { x -> x.name.endsWith(".kt") }!!.filter { it.name !in setOf("Appearance.kt", "ThemeArt.kt") }) {
            assertTrue("${f.name} draws a button radius of its own", "buttonRadius" !in f.readText())
        }
        for ((file, count) in listOf("GroupScreens.kt" to 2, "OverviewTab.kt" to 1, "Invites.kt" to 1)) {
            assertEquals(file, count, src(file).split("ThemeArt.controlShape(").size - 1)
        }
    }

    /** F4: Vardr Labs uppercases a button's words, except an identifier that keeps its case (an @username
     *  suggestion: handles are lowercase); the other themes never change the case. */
    @Test
    fun only_vardr_labs_buttons_uppercase_and_an_identifier_keeps_its_case() {
        assertEquals("SAVE", ButtonLook.label("Save", AppTheme.VARDR))
        assertEquals("@asha.k", ButtonLook.label("@asha.k", AppTheme.VARDR, keepsCase = true))
        for (theme in listOf(AppTheme.AURORA, AppTheme.NORSE)) {
            assertEquals("Save", ButtonLook.label("Save", theme))
            assertEquals("@asha.k", ButtonLook.label("@asha.k", theme, keepsCase = true))
        }
        assertTrue("the @username chips", "SecondaryAction(\"@\$s\", dark, keepsCase = true)" in src("UsernameStep.kt"))
        val actions = src("GroupScreens.kt")
        assertTrue("no button uppercases around ButtonLook.label", "label.uppercase(" !in actions)
        assertEquals("both outlined bodies", 2, Regex("""ButtonLook\.label\(""").findAll(actions).count())
    }

    // ---- D-215 (B5): the runes are drawn in Vardr Runes Carved, bundled ----

    private val runeFile = java.io.File("src/main/res/font/vardrrunes_carvedregular.ttf")

    /** The JVM's own font reader loads the bundled file and says which code points it draws (the check the phone's
     *  `Paint.hasGlyph` proof used to make): every rune SKIPTI draws, and not U+16F9 (the block's first unassigned
     *  code point), so a "yes" is not vacuous. */
    @Test
    fun every_rune_is_in_the_bundled_rune_font() {
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, runeFile)
        assertTrue("family is ${font.family}", font.family.startsWith("Vardr Runes Carved"))
        for (r in Runes.ALL) assertTrue("U+%04X".format(r.codePointAt(0)), font.canDisplay(r.codePointAt(0)))
        assertFalse("U+16F9 is unassigned", font.canDisplay(0x16F9))
    }

    /** Android's copy is the file iOS bundles, byte for byte, and `Type.runes` is that one resource. */
    @Test
    fun the_android_rune_font_is_the_ios_file_and_type_runes_names_it() {
        assertEquals(
            java.io.File("../../native/fonts/VardrRunes-CarvedRegular.ttf").readBytes().toList(),
            runeFile.readBytes().toList(),
        )
        val fonts = (Type.runes as androidx.compose.ui.text.font.FontListFontFamily).fonts
        val only = fonts.single() as androidx.compose.ui.text.font.ResourceFont
        assertEquals(R.font.vardrrunes_carvedregular, only.resId)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, only.weight)
    }

    /** One weight, never synthesised: a rune in a SemiBold or Bold line must not be emboldened (iOS never does). */
    @Test
    fun a_rune_is_always_regular_and_never_synthesised_bold() {
        for (style in listOf(
            RuneSpans.style.fontFamily to (RuneSpans.style.fontWeight to RuneSpans.style.fontSynthesis),
            Type.rune(11.sp).fontFamily to (Type.rune(11.sp).fontWeight to Type.rune(11.sp).fontSynthesis),
        )) {
            assertEquals(Type.runes, style.first)
            assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, style.second.first)
            assertEquals(androidx.compose.ui.text.font.FontSynthesis.None, style.second.second)
        }
        // The span takes the line's colour and size; the art style takes a size and no colour.
        assertEquals(androidx.compose.ui.graphics.Color.Unspecified, RuneSpans.style.color)
        assertEquals(androidx.compose.ui.unit.TextUnit.Unspecified, RuneSpans.style.fontSize)
        assertEquals(13.sp, Type.rune(13.sp).fontSize)
        assertEquals(androidx.compose.ui.graphics.Color.Unspecified, Type.rune(13.sp).color)
    }

    @Test
    fun a_rune_inside_plain_text_takes_the_font_only_and_keeps_the_lines_colour() {
        val line = "Asha owes ₹200.00 ᛫ Bo is owed ₹50.00 here".withRunes()
        assertEquals(listOf("᛫"), line.spanStyles.map { line.text.substring(it.start, it.end) })
        assertEquals(RuneSpans.style, line.spanStyles.single().item)
        assertTrue("no colour", line.spanStyles.all { it.item.color == androidx.compose.ui.graphics.Color.Unspecified })
        assertTrue("plain text has no span", "Goa · INR".withRunes().spanStyles.isEmpty())
        assertEquals("", "".withRunes().text)
        // The whole block, and nothing outside it.
        assertTrue(RuneSpans.isRune('ᚠ') && RuneSpans.isRune('ᛸ'))
        assertFalse(RuneSpans.isRune('᚟') || RuneSpans.isRune('᛹') || RuneSpans.isRune('·'))
    }

    // The leaks the guard looks for, as a pure function so it can be tried on samples as well as on the tree.
    // D-215 fix wave (B5-m1): the whole Runic block, U+16A0 to U+16F8, as a literal character or a `\u` escape.
    private val producer = Regex("""Separator\s*\.\s*meta\s*\(|\bRunes\s*\.\s*[A-Z_]+|[\u16A0-\u16F8]|\\u16([A-Ea-e][0-9A-Fa-f]|[Ff][0-8])""")
    private val drawn = listOf("withRunes(", "Separator.annotated(", "SectionLabels.annotated(", "Type.rune(")

    private fun callArgs(src: String, from: Int): String {
        var depth = 0
        var i = from
        while (i < src.length) {
            if (src[i] == '(') depth++
            if (src[i] == ')') { depth--; if (depth == 0) break }
            i++
        }
        return src.substring(from, minOf(i + 1, src.length))
    }

    /** One argument's value, from [from] to the next top-level comma or the call's closing bracket. */
    private fun argValue(src: String, from: Int): String {
        var depth = 0
        var i = from
        while (i < src.length) {
            val c = src[i]
            if (c == '(' || c == '[' || c == '{') depth++
            if (c == ')' || c == ']' || c == '}') { if (depth == 0) break; depth-- }
            if (c == ',' && depth == 0) break
            i++
        }
        return src.substring(from, i)
    }

    /** Material's text slots: a dialog's title and text, a text field's label and placeholder. */
    private val slotCalls = Regex("""(?<![A-Za-z0-9_])(SkiptiAlertDialog|AlertDialog|OutlinedTextField|TextField)\s*\(""")
    private val slotNames = Regex("""(?<![A-Za-z0-9_])(title|text|label|placeholder)\s*=(?!=)\s*""")

    /** A `Text(`, `BasicText(` or `.measure(` whose arguments hold a rune value (a `Runes.` constant, a Runic
     *  character or `\u16xx` escape, or a `Separator.meta()` string) must also hold what draws it in the rune font;
     *  so must a dialog's `title =` / `text =` and a text field's `label =` / `placeholder =` slot. */
    private fun runeLeaks(name: String, raw: String): List<String> {
        val src = raw.replace(Regex("/\\*[\\s\\S]*?\\*/")) { "\n".repeat(it.value.count { c -> c == '\n' }) }.replace(Regex("(?<![:\"])//.*"), "")
        val out = mutableListOf<String>()
        fun line(at: Int) = src.substring(0, at).count { it == '\n' } + 1
        for (m in Regex("""(?<![A-Za-z0-9_])(BasicText|Text)\s*\(|\.measure\s*\(""").findAll(src)) {
            val args = callArgs(src, m.range.last)
            if (producer.containsMatchIn(args) && drawn.none { it in args }) {
                out += "$name: ${m.value.trim()} shows a rune as a plain String at line ${line(m.range.first)}"
            }
        }
        for (m in slotCalls.findAll(src)) {
            val args = callArgs(src, m.range.last)
            val wanted = if (m.groupValues[1].endsWith("Dialog")) setOf("title", "text") else setOf("label", "placeholder")
            for (slot in slotNames.findAll(args)) {
                if (slot.groupValues[1] !in wanted) continue
                val value = argValue(args, slot.range.last + 1)
                if (producer.containsMatchIn(value) && drawn.none { it in value }) {
                    out += "$name: ${m.groupValues[1]} ${slot.groupValues[1]} shows a rune as a plain String at line ${line(m.range.first)}"
                }
            }
        }
        return out
    }

    @Test
    fun the_rune_leak_guard_flags_what_it_claims_to() {
        val bad = listOf(
            "Text(Runes.ORNAMENT, style = TextStyle(fontSize = 9.sp))",
            "Text(\"a\${Separator.meta()}b\", style = x)",
            "Text(\"a \\u16EB b\")",
            "Text(Type.kicker(\"a\${Separator.meta()}b\"), style = y)",
            "val g = measurer.measure(Runes.BALANCE, TextStyle(fontSize = 92.dp.toSp()))",
            "androidx.compose.material3.Text(Separator.meta(), color = c)",
            "Text(\"a \u16EB b\")",
            "Text(\"rune \u16f8\", style = x)",
            "BasicText(Runes.ORNAMENT, style = s)",
            "SkiptiAlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Heading(\"a\${Separator.meta()}b\") })",
            "androidx.compose.material3.AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Body(Runes.SEPARATOR) })",
            "OutlinedTextField(value = v, onValueChange = {}, label = { Caption(\"\u16A0\") })",
            "TextField(value = v, onValueChange = {}, placeholder = { Caption(Runes.BALANCE) })",
        )
        for (src in bad) assertTrue("flags: $src", runeLeaks("sample.kt", src).isNotEmpty())
        val good = listOf(
            "Text(Runes.ORNAMENT, style = Type.rune(runeSize(\"ornamentRune\", d)).copy(color = c))",
            "Text(\"a\${Separator.meta()}b\".withRunes(), style = x)",
            // Each drawing wrapper gets a sample with a rune inside it, so removing it from `drawn` turns the sample red
            // (D-215 sabotage A132 and A134: a sample with no rune cannot tell whether the wrapper is still counted).
            "Text(Separator.annotated(listOf(\"Food \u16EB Dining\", b)), style = y)",
            "Text(SectionLabels.annotated(\"Rates \u16EB EUR\"), style = y)",
            "val g = measurer.measure(Runes.BALANCE, Type.rune(ThemeArt.m(\"heroWatermark\").dp.toSp()))",
            "// Text(Runes.ORNAMENT) in a comment\nval x = 1",
            "Text(name, style = z)",
            "Text(\"\u1680 \u169F \u16F9\")",
            "Text(\"\\u169F \\u16F9 \\u1680\")",
            "BasicText(\"a \u16EB b\".withRunes(), style = s)",
            "SkiptiAlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(\"a\${Separator.meta()}b\".withRunes()) })",
            "OutlinedTextField(value = v, onValueChange = {}, label = { Text(AddSheetCopy.WHAT_FOR) })",
        )
        for (src in good) assertEquals("passes: $src", emptyList<String>(), runeLeaks("sample.kt", src))
    }

    /** The calls of `border` or `drawOutline` in [text] whose arguments, to the matching parenthesis, name norseEdge. */
    private fun wholeOutlineEdges(text: String): List<String> = Regex("""\b(border|drawOutline)\(""").findAll(text).mapNotNull { m ->
        var depth = 0
        var end = text.length
        for (i in m.range.last until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { end = i; break }
            }
        }
        m.groupValues[1].takeIf { "norseEdge" in text.substring(m.range.last, end) }
    }.toList()

    private fun mainSources() = java.io.File("src/main/java/com/vardrlabs/skipti").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }.toList()
        // D-220 fix wave (Minor 16): a scan over an empty list passes anything.
        .also { check(it.size > 50) { "main sources not found: ${it.size}" } }

    /** D-215: no `Separator.meta` or `Runes.` value reaches a `Text(` as a plain String. Direct uses are found by
     *  [runeLeaks]; a meta line built in a helper and shown elsewhere is pinned instead: every `Separator.meta(` in a
     *  file other than ThemeArt.kt is counted below, and so is the `.withRunes()` at the Text that shows each one, so
     *  a new producer, or a render site that loses its wrapper, fails here. */
    @Test
    fun no_rune_reaches_a_text_as_a_plain_string() {
        val sources = mainSources()
        assertTrue("sources found", sources.size > 40)
        assertEquals(emptyList<String>(), sources.flatMap { runeLeaks(it.name, it.readText()) })

        val producers = mapOf(
            // iOS twin in brackets. GroupSettings.stillOpen [GroupSettings.swift:73]
            "GroupSettings.kt" to 1,
            // ExpenseListUi.categoryLabel [ExpenseListView.swift:348]
            "ExpenseListScreen.kt" to 1,
            // FoundPerson.detail [People.swift:218]
            "People.kt" to 1,
            // BalanceUi.headerFor (a SectionLabel, two separators) and the two staleness lines [BalanceScreen.swift:416, 203, 204]
            "BalanceScreen.kt" to 4,
            // GroupsCopy.detail [GroupsScreen.swift:322]
            "GroupsTab.kt" to 1,
            // OverviewCopy.settlesIn [OverviewScreen.swift:29]
            "OverviewTab.kt" to 1,
            // RolesCopy.balanceHere [Roles.swift:185]
            "Roles.kt" to 1,
            // the two staleness lines and the "Settle in" SectionLabel [SettleScreen.swift:94, 95, 113]
            "SettleScreen.kt" to 3,
        )
        val actual = sources.filter { it.name != "ThemeArt.kt" }
            .associate { it.name to Regex("""Separator\s*\.\s*meta\s*\(""").findAll(it.readText()).count() }.filterValues { it > 0 }
        assertEquals("every Separator.meta( producer is classified", producers, actual)

        // The Text that shows each plain-String producer wraps it (SectionLabel does so inside: see the next test).
        val renders = listOf(
            Triple("GroupSettings.kt", "Text(open.withRunes(), style = Type.body(dark).copy(fontSize = 14.sp))", 1),
            Triple("ExpenseListScreen.kt", "Text(title.withRunes(), style = Type.name(dark, 15), maxLines = 2, overflow = TextOverflow.Ellipsis)", 1),
            Triple("People.kt", "person.detail(contacts)?.let { Text(it.withRunes(), style = Type.bodyDim(dark)) }", 1),
            Triple("BalanceScreen.kt", "}).withRunes(),\n                        style = Type.microLabel(dark).copy(color = Tok.owed(dark)),", 1),
            Triple("SettleScreen.kt", "}).withRunes(),\n                        style = Type.microLabel(dark).copy(color = Tok.owed(dark)),", 1),
            // D-220 (L25): the photo lines only; the plain card and the tile row draw their parts with Separator.annotated.
            Triple("GroupsTab.kt", "GroupsCopy.detail(named.members.size, named.group.settlementCurrency).withRunes(),", 1),
            Triple("OverviewTab.kt", "OverviewCopy.settlesIn(n.members.size, n.group.settlementCurrency).withRunes()", 1),
            Triple("GroupsTab.kt", "Separator.annotated(GroupsCopy.detailParts(", 1),
            Triple("OverviewTab.kt", "Separator.annotated(OverviewCopy.settlesInParts(", 1),
            Triple("RemovalFlow.kt", "if (sub != null) Text(sub.withRunes(), style = Type.bodyDim(dark)", 1),
            Triple("RemovalFlow.kt", "balance?.let { Text(it.withRunes(), style = Type.bodyDim(dark)", 2),
            Triple("RemovalFlow.kt", "Text(banner.first.withRunes(), style = Type.label(dark, 15)", 1),
            // D-225 T5: a licence row's "version, licence" is a ListRow summary now (Separator.annotated, ListRows.kt).
        )
        for ((file, snippet, count) in renders) {
            val text = sources.first { it.name == file }.readText()
            assertEquals("$file: $snippet", count, text.split(snippet).size - 1)
        }
    }

    /** D-215 fix 1, D-225 T7 (Design 11): the Add expense sheet's Group line is "Group" on the left and the name
     *  right-aligned in textDim, with no separator (so no rune and no middle dot) between them: now the list row's value
     *  piece (a chevron only when it opens the choice page), as iOS's `.value` row. iOS's LabeledContent leaves in Task 6,
     *  so the Swift is no longer read here. */
    @Test
    fun the_add_expense_group_line_is_a_label_left_and_a_dim_name_right() {
        val add = src("AddExpenseScreen.kt")
        val branch = add.substringAfter("if (shown != null && groupRow != AddExpenseRows.GroupRow.NONE) {").substringBefore("SectionLabel(AddSheetCopy.SPLIT, dark)")
        assertFalse("no separator", "Separator" in branch)
        assertTrue("the label is the row's title", "AddSheetCopy.GROUP, dark," in branch)
        assertTrue("the name is the row's value", "trailing = RowTrailing.Value(shown.group.name, chevron = chooser)," in branch)
        val value = src("ListRows.kt").substringAfter("is RowTrailing.Value -> Row(").substringBefore("is RowTrailing.Piece")
        assertTrue("right-aligned", "horizontalArrangement = Arrangement.End" in value)
        assertTrue("in textDim", "style = Type.bodyDim(dark)," in value)
    }

    /** A section label's words go through `SectionLabels.annotated` (the rune font on every rune in them), and the
     *  meta line builder through `appendWithRunes`: the two builders every other site relies on. */
    @Test
    fun the_span_builders_put_the_rune_font_on_the_rune_range() {
        val art = src("ThemeArt.kt")
        assertTrue("section label words", "appendWithRunes(if (theme == AppTheme.VARDR) text.uppercase(java.util.Locale.getDefault()) else text)" in art)
        assertTrue("section mark", "withStyle(SpanStyle(color = Tok.accent.norse, letterSpacing = 0.sp)) { appendWithRunes(it) }" in art)
        assertTrue("separator", "withStyle(SpanStyle(color = Tok.accent.norse)) { appendWithRunes(meta(theme)) }" in art)
        assertTrue("part", "appendWithRunes(part)" in art)
        assertTrue("SectionLabel draws the annotated words", "SectionLabels.annotated(text)," in art)
        assertTrue("the style names the font", "fontFamily = Type.runes," in art.substringAfter("object RuneSpans").substringBefore("fun isRune"))
        // The drawn art: each fixed-size site names the rune style, once.
        assertEquals(1, src("TabBar.kt").split("Type.rune(runeSize(\"tabRune\", LocalDensity.current)).copy(color = tint)").size - 1)
        assertEquals(1, art.split("Type.rune(runeSize(\"ornamentRune\", LocalDensity.current)).copy(color = Tok.accent(dark))").size - 1)
        val motion = src("ThemeMotion.kt")
        assertEquals(1, motion.split("measurer.measure(p.glyph, Type.rune(p.size.toFloat().dp.toSp()))").size - 1)
        assertEquals(1, motion.split("measurer.measure(Runes.BALANCE, Type.rune(ThemeArt.m(\"heroWatermark\").dp.toSp()))").size - 1)
        // D-221: Norse's rune ring round You's picture, fixed size like the other art.
        assertEquals(1, src("YouProgress.kt").split("val style = Type.rune(runeSize(\"runeRingSize\", density))").size - 1)
        // D-221 Task 9: the settled burst's Norse particle, fixed size like the other art (fix wave S6: the 24 measured once).
        assertEquals(1, motion.split("measurer.measure(it, Type.rune(with(density) { SettledBurst.NORSE_RUNE.toFloat().dp.toSp() }))").size - 1)
        // And Type.rune( is used nowhere else.
        val users = mainSources().filter { it.name != "Tokens.kt" }.associate { it.name to Regex("""Type\s*\.\s*rune\s*\(""").findAll(it.readText()).count() }.filterValues { it > 0 }
        // D-222 (L61): the Norse swatch's four runes on Appearance.
        assertEquals(1, src("AccountHubScreen.kt").split("style = Type.rune(runeSize(\"swatchRune\", density))").size - 1)
        // D-245 (decision 192, 197): the welcome's progress runes (measured, drawn as art) and W1's places in Norse (the
        // bar's own runes, alone in their tile).
        val welcome = src("Welcome.kt")
        assertEquals(1, welcome.split("val style = Type.rune(with(density) { WelcomeProgress.RUNE_SIZE.dp.toSp() })").size - 1)
        assertEquals(1, welcome.split("style = Type.rune(with(LocalDensity.current) { runeDp.dp.toSp() }).copy(color = tint),").size - 1)
        assertEquals(mapOf("TabBar.kt" to 1, "ThemeArt.kt" to 1, "ThemeMotion.kt" to 3, "YouProgress.kt" to 1, "AccountHubScreen.kt" to 1, "Welcome.kt" to 2), users)
    }

    // ---- D-220 Task 3: the Android call sites take the decisions (twins of iOS's ThemeArtTests guards) ----

    /** A top-level function's text, from [decl] to the first closing brace at column 0. */
    private fun funBody(file: String, decl: String): String {
        val text = src(file)
        val at = text.indexOf(decl)
        assertTrue("$file declares $decl", at >= 0)
        val end = text.indexOf("\n}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    /** The group page's photo band, from its Box to the tile row that replaces it. */
    private fun photoBand(): String {
        val o = src("OverviewTab.kt")
        val start = o.indexOf("if (photo != null) Box(")
        val end = o.indexOf("} else Row(verticalAlignment = Alignment.CenterVertically)", start)
        assertTrue("the photo band is found: $start, $end", start >= 0 && end > start)
        return o.substring(start, end)
    }

    /** D-220 (L19): every pill is `themePill`: no capsule is drawn outside the pill decision itself and C1's bars, each
     *  site names its tone (and `onPage` where it sits on the page ground), and its words take the pill's ink. */
    @Test
    fun every_pill_is_theme_pill() {
        val capsules = mainSources().filter { it.name !in setOf("ThemeArt.kt", "TabBar.kt", "SkiptiBar.kt") }
            .filter { "RoundedCornerShape(50)" in it.readText() }.map { it.name }.sorted()
        assertEquals(emptyList<String>(), capsules)
        fun count(file: String, s: String) = src(file).split(s).size - 1
        val sites = listOf(
            Triple("OverviewTab.kt", ".themePill(PillTone.HERO, dark)", 1),
            Triple("OverviewTab.kt", ".themePill(PillTone.PHOTO, dark)", 1),
            Triple("ExpenseListScreen.kt", "val tone = if (on) PillTone.SELECTED else PillTone.UNSELECTED", 1),
            Triple("ExpenseListScreen.kt", ".themePill(tone, dark, onPage = true)", 1),
            Triple("GroupsTab.kt", ".themePill(PillTone.ACCENT, dark)", 1),
            Triple("GroupsTab.kt", ".themePill(PillTone.PHOTO, dark)", 1),
            Triple("RemovalFlow.kt", ".themePill(tone, dark)", 1),
            Triple("AddExpenseScreen.kt", ".themePill(PillTone.ACCENT, dark, onPage = true)", 1),
            Triple("People.kt", ".themePill(tone, dark)", 1),
        )
        for ((file, s, n) in sites) assertEquals("$file: $s", n, count(file, s))
        for (file in sites.map { it.first }.distinct()) assertTrue("$file: the words take the pill's ink", "ThemePills.text(" in src(file))
        val role = funBody("RemovalFlow.kt", "fun RolePill(")
        assertTrue("the owner pill", "RoleRules.Role.OWNER -> RolesCopy.OWNER_PILL to PillTone.OWNER" in role)
        assertTrue("the admin pill", "RoleRules.Role.ADMIN -> RolesCopy.ADMIN_PILL to PillTone.ACCENT" in role)
        // The photo band's add-people button is the photo pill at iOS's 40 dp, not a circle, inside a 48 dp touch target
        // (fix wave I2) that takes the tap as a button.
        val band = photoBand()
        assertFalse("the band's button is no circle", "CircleShape" in band)
        assertTrue("the band's button is the photo pill", "Modifier.size(40.dp).themePill(PillTone.PHOTO, dark)" in band)
        assertTrue(
            "a 48 dp touch target round it",
            Regex("""\.padding\(4\.dp\)\s*\.size\(48\.dp\)\s*\.clickable\(role = Role\.Button\) \{ onPeople\(n\.group\.id\) \}""")
                .containsMatchIn(band),
        )
        // The modifier itself: the pill's shape, then PillPaint's fill and border inside it.
        val pill = funBody("ThemeArt.kt", "fun Modifier.themePill(")
        assertTrue("the shape", "ThemeArt.pillShape(theme)" in pill)
        assertTrue("the paint", "PillPaint.of(tone, PillLook.of(theme), onPage)" in pill)
        assertTrue("the border inside the shape", ".border(PillPaint.BORDER_WIDTH.dp," in pill)
    }

    /** D-220 (L21): a photo takes the card's frame: the card's shape, Norse's edge, Vardr Labs' white marks. */
    @Test
    fun photos_take_the_cards_frame() {
        val card = funBody("GroupsTab.kt", "private fun PhotoGroupCard(")
        assertTrue("the Groups photo card", ".photoFrame(null, dark)" in card)
        assertFalse("no own clip on the photo card", "RoundedCornerShape(Appearance.theme.cardRadius.dp)" in card)
        val band = photoBand()
        assertTrue("the group page's band", ".photoFrame(18, dark)" in band)
        assertFalse("no own clip on the band", "RoundedCornerShape(18.dp)" in band)
        val frame = funBody("ThemeArt.kt", "fun Modifier.photoFrame(")
        assertTrue("the card's shape", "ThemeArt.cardShape(theme, radius)" in frame)
        // D-226 (L35): Norse's edge on the straight sides (the_norse_card_edge_is_the_straight_sides).
        assertTrue("Norse's edge", "notchEdge(ThemeArt.color(\"norseEdge\"), ThemeArt.m(\"chamfer\"))" in frame)
        assertTrue("Vardr Labs' white marks", "Color.White.copy(alpha = ThemeArt.m(\"frameAlpha\"))" in frame)
        assertTrue("the marks' geometry", "ArtGeometry.frameMarks(" in frame)
    }

    /** D-220 fix wave (I3): where the theme decides a card's shape, no screen draws its own rounded one: no
     *  `RoundedCornerShape` from the card radius and no `cardRadius(` outside the decisions (ThemeArt.kt) and the glass
     *  card itself (Aurora.kt). The hero cards, the removal ring and the ripple clips take `ThemeArt.cardShape`.
     *  D-222 (S1): the theme chooser's miniature of another theme's card, the one exception, is gone. */
    @Test
    fun no_screen_draws_its_own_card_shape() {
        val own = Regex("""RoundedCornerShape\([^\n]*cardRadius|\bcardRadius\(""")
        val allowed = emptySet<Pair<String, String>>()
        val leaks = mainSources().filter { it.name !in setOf("ThemeArt.kt", "Aurora.kt") }.flatMap { f ->
            f.readLines().filter { own.containsMatchIn(it) && (f.name to it.trim()) !in allowed }.map { "${f.name}: ${it.trim()}" }
        }
        assertEquals(emptyList<String>(), leaks)
        fun count(file: String, s: String) = src(file).split(s).size - 1
        val sites = listOf(
            Triple("OverviewTab.kt", ".heroArt(dark, ThemeArt.cardShape(Appearance.theme))", 1),
            Triple("SignInScreen.kt", ".heroArt(dark, ThemeArt.cardShape(Appearance.theme))", 1),
            Triple("RemovalFlow.kt", "Modifier.border(2.dp, Tok.accent(dark), ThemeArt.cardShape(Appearance.theme))", 1),
            // D-223: the By group card's clip went with its scroller (L55); the ledger card takes the card's own shape.
            Triple("OverviewTab.kt", ".clip(ThemeArt.cardShape(Appearance.theme, 20))", 0),
            Triple("HomeParts.kt", ".clip(ThemeArt.cardShape(Appearance.theme))", 1),
            // D-243 fix wave (M7): Profile.kt's ProfileCard had no call site anywhere and went; its clip with it.
            Triple("Profile.kt", ".clip(ThemeArt.cardShape(Appearance.theme))", 0),
            Triple("AccountHubScreen.kt", ".clip(ThemeArt.cardShape(Appearance.theme))", 1),
        )
        for ((file, s, n) in sites) assertEquals("$file: $s", n, count(file, s))
    }

    /** D-220 (L22): the category bar is the meter shape and its legend swatch the swatch shape. */
    @Test
    fun the_category_bar_is_the_meter_shape() {
        val o = src("OverviewTab.kt")
        assertEquals(1, o.split(".clip(ThemeArt.meterShape(Appearance.theme))").size - 1)
        assertFalse("no own capsule on the bar", ".clip(RoundedCornerShape(5.dp))" in o)
        assertEquals(1, o.split("ThemeArt.swatchShape(Appearance.theme)").size - 1)
        assertFalse("no own swatch corner", "RoundedCornerShape(2.dp)" in o)
    }

    /** D-220 (L23): a tile keeps its `size` footprint and draws its shape at `tileDrawnSide` inside it (a Norse diamond
     *  at size x sqrt 2); the emoji is half the size on every shape. */
    @Test
    fun tiles_draw_the_diamond_at_its_side() {
        for ((file, decl) in listOf("GroupsTab.kt" to "fun PictureTile(", "RemovalFlow.kt" to "fun PersonAvatar(")) {
            val body = funBody(file, decl)
            val footprint = body.indexOf("Box(Modifier.size(size.dp)")
            val drawn = body.indexOf(".requiredSize(tileDrawnSide(kind, size).dp)")
            assertTrue("$file: the footprint", footprint >= 0)
            assertTrue("$file: the drawn tile inside the footprint", drawn > footprint)
        }
        val tile = funBody("GroupsTab.kt", "fun PictureTile(")
        assertFalse("no diamond emoji branch", "0.4f else 0.5f" in tile)
        assertTrue("the emoji at half the size", "fontSize = (size * 0.5f).sp" in tile)
    }

    /** D-220 (L24): Vardr Labs' hero Settle up is outlined: transparent, a 1 dp onHero border, its words through
     *  ButtonLook at .08 em, an onHero film when pressed. Aurora and Norse keep the filled slab. */
    @Test
    fun vardr_hero_settle_is_outlined() {
        val hero = funBody("OverviewTab.kt", "private fun Hero(")
        val branch = hero.indexOf("if (ButtonLook.of(Appearance.theme) == ButtonLook.OUTLINED)")
        assertTrue("the hero branches on the look", branch >= 0)
        val outlined = hero.substring(branch)
        assertTrue("the border", ".border(1.dp, Tok.onHero(dark), RectangleShape)" in outlined)
        assertTrue("the pressed film", "Tok.onHero(dark).copy(alpha = if (pressed) Aurora.HERO_CHIP_ALPHA else 0f)" in outlined)
        assertTrue("the words", "ButtonLook.label(OverviewCopy.SETTLE_UP, Appearance.theme)" in outlined)
        assertTrue("the tracking", "letterSpacing = 0.08.em" in outlined)
        assertTrue("the filled slab stays", ".background(Tok.onHero(dark))" in outlined)
    }

    /** D-220 (L-D217-4): a disabled button draws its ENABLED colours at ButtonLook.DISABLED_ALPHA, in both looks. */
    @Test
    fun disabled_buttons_dim_the_enabled_look() {
        val g = src("GroupScreens.kt")
        assertFalse("no disabled container colour", "disabledContainerColor = Tok.surfaceRaised" in g)
        assertFalse("no disabled content colour", "disabledContentColor = Tok.textFaint" in g)
        for (decl in listOf("internal fun PrimaryAction(", "internal fun TonalAction(")) {
            val body = funBody("GroupScreens.kt", decl)
            assertEquals("$decl dims both looks", 2, body.split(".alpha(if (enabled) 1f else ButtonLook.DISABLED_ALPHA)").size - 1)
            assertFalse("$decl draws no faint colour", "Tok.textFaint" in body)
        }
    }

    /** D-220 (L-D217-4): Profile's "Save name" takes the full width, as iOS's SecondaryButtonStyle does. */
    @Test
    fun save_name_is_full_width() {
        val p = src("Profile.kt")
        // D-224 (Design 8): the label no longer turns "Saved" (the toast says it), so the call starts with its one label.
        // D-245 (U17): "Save name" is the PRIMARY style while there is a name to save, the secondary dimmed when not
        // (iOS's twin); both take the full width.
        val at = p.indexOf("SecondaryAction(WelcomeCopy.SAVE_NAME")
        assertTrue("the call is found", at >= 0)
        val call = p.substring(at).substringBefore("\n")
        assertTrue("it dims when there is nothing to save", "enabled = false" in call)
        assertFalse("no wrapped width", "modifier = Modifier)" in call)
        assertTrue("the primary when savable", "if (savable) {\n                PrimaryAction(WelcomeCopy.SAVE_NAME, dark) { saveName() }" in p)
    }

    // ---- D-225 Task 3: the shared list components (the twins of iOS's Task 2 guards) ----

    /** A top-level declaration's text in ListRows.kt, to its closing brace at column 0. */
    private fun rows(decl: String): String = funBody("ListRows.kt", decl)

    /** D-225 (Design 1; L67, L73, L74, L-D217-1): ONE list row and ONE list card. The row: the title in the name role at
     *  16 (`owed` under the danger tone), the summary as parts joined by the theme's separator at the detail size, the
     *  line gap, the row padding and gap, the 48 dp minimum, the chevron; the card: S1's card at padding 0 and the list
     *  padding, a hairline BETWEEN rows (none after the last). You's list calls the row. The twin of iOS's
     *  testTheListRowIsOneComponent. */
    @Test
    fun the_list_row_is_one_component() {
        val row = rows("fun ListRow(")
        for (s in listOf(
            "Type.name(dark, 16)", "Separator.annotated(parts)", "TypeScale.detail.sp", "ThemeArt.m(\"rowLineGap\")",
            "ThemeArt.m(\"rowPadV\")", "ThemeArt.m(\"rowGap\")", ".heightIn(min = ThemeArt.m(\"touchTarget\").dp)",
            "if (tone == RowTone.DANGER) Tok.owed(dark) else Tok.text(dark)", "Modifier.clearAndSetSemantics {}",
            "contentDescription = ListRows.spoken(title, parts, trailing)", "clickable(enabled = enabled, role = Role.Button",
        )) assertTrue("ListRow: $s", s in row)
        assertTrue("one line by default, unlimited at 0", "summaryLines: Int = 1" in row && "if (summaryLines == 0) Int.MAX_VALUE else summaryLines" in row)
        val end = rows("private fun RowEnd(")
        for (s in listOf("RowChevron(Tok.textDim(dark))", "ThemeSwitch(trailing.isOn, dark, enabled)", "TabIcons.Check", "tint = Tok.accent(dark)", "Type.bodyDim(dark)", "maxLines = 1")) {
            assertTrue("RowEnd: $s", s in end)
        }
        val card = rows("fun ListCard(")
        for (s in listOf("glassCard(dark, padding = 0)", "ThemeArt.m(\"listPadH\")", "ThemeArt.m(\"listPadV\")", "Tok.hairline(dark)")) {
            assertTrue("ListCard: $s", s in card)
        }
        assertEquals("one divider, between rows only", 1, Regex("""Tok\.hairline\(dark\)""").findAll(card).count())
        assertTrue("the divider comes before every row but the first", Regex("""if \(i > 0\) Box\(Modifier\.fillMaxWidth\(\)\.height\(1\.dp\)\.background\(Tok\.hairline\(dark\)\)\)\s*row\(\)""").containsMatchIn(card))
        // You's list: one card, a row per section, each a ListRow with its tile and chevron.
        val hub = src("AccountHubScreen.kt")
        val list = hub.substringAfter("SettingsSection.entries.forEach").substringBefore("return@PushedPage")
        for (s in listOf("ListRow(", "leading = { SettingsTile(s.glyphName, dark) }", "parts = YouRows.parts(s, rowFacts)", "trailing = RowTrailing.Chevron")) {
            assertTrue("You's list: $s", s in list)
        }
        assertTrue("You's list is a ListCard", "ListCard(dark) {\n                SettingsSection.entries.forEach" in hub)
        // The spoken line: title then parts (then a value), never the separator; a chevron row reads as You's did.
        assertEquals("Security, App lock off", ListRows.spoken("Security", listOf("App lock off"), RowTrailing.Chevron))
        assertEquals(YouRows.spoken("About", listOf("1.0", "promises")), ListRows.spoken("About", listOf("1.0", "promises"), RowTrailing.Chevron))
        assertEquals("Settles in, INR", ListRows.spoken("Settles in", emptyList(), RowTrailing.Value("INR", chevron = false)))
        assertEquals("Haptics", ListRows.spoken("Haptics", emptyList(), RowTrailing.Toggle(true) {}))
    }

    /** D-225 (Design 3; L-D217-9): the drawn switch: iOS's proportions from the five switch metrics, the track the theme's
     *  pill shape, the thumb the theme's person tile inscribed in its box with S1's segment shadow, the paint from
     *  `SwitchPaint`, the place from `SwitchGeometry`, the slide `switchTravel` long and snapped under the D-214 still rule,
     *  dimmed when disabled, and silent to TalkBack (the row speaks). The twin of iOS's testTheSwitchIsDrawnAndAccessible. */
    @Test
    fun the_switch_is_drawn_and_accessible() {
        val sw = rows("fun ThemeSwitch(")
        for (s in listOf(
            "SwitchPaint.of(isOn)", "SwitchGeometry.thumbCentreX(isOn)", "ThemeArt.pillShape(theme)", "TileShape.of(theme, person = true)",
            "ThemeArt.m(\"switchWidth\")", "ThemeArt.m(\"switchHeight\")", "ThemeArt.m(\"switchThumb\")", "ThemeArt.m(\"switchInset\")",
            "ThemeArt.m(\"switchBorder\")", "ThemeArt.m(\"switchTravel\")", "ButtonLook.DISABLED_ALPHA", "animateFloatAsState(",
            "val still = motion.reduce", "if (still) snap() else tween(", "easing = EaseInOut",
            "shadow(ThemeArt.m(\"segmentShadowRadius\").dp, thumbShape)", "clearAndSetSemantics {}",
        )) assertTrue("ThemeSwitch: $s", s in sw)
        for (gone in listOf("CircleShape", "RoundedCornerShape", "androidx.compose.material3.Switch")) assertFalse("ThemeSwitch draws $gone", gone in sw)
    }

    /** D-225 (Design 10): the choice page is a radio group: the card `selectableGroup()`, every option a `.check` row
     *  (`selectable` with the RadioButton role, in ListRow), picking writes then returns. The twin of iOS's
     *  testTheChoicePageChecksOneRow. */
    @Test
    fun the_choice_page_is_a_radio_group() {
        val page = rows("fun <T> ChoicePage(")
        for (s in listOf("ListCard(dark, Modifier.selectableGroup())", "ListRow(option.label, dark, parts = option.parts, trailing = RowTrailing.Check(option.value == chosen))", "PushedWindow(title, dark, onClose)", "caption?.let { CardCaption(it, dark) }")) {
            assertTrue("ChoicePage: $s", s in page)
        }
        assertTrue("picks, then returns unless the option waits on a confirmation", Regex("""onPick\(option\.value\)\s*if \(!stays\(option\.value\)\) onClose\(\)""").containsMatchIn(page))
        val row = rows("fun ListRow(")
        // D-216 (the look's F3): the role is the decision's, a RadioButton for every choice-page row (their check rows
        // never set `acts`, pinned by the exact ListRow call above); AppearanceTest's
        // a_chosen_row_that_still_acts_stays_clickable holds the decision.
        assertTrue("a check row is a radio button", Regex("""is RowTrailing\.Check -> Modifier\.selectable\(\s*selected = trailing\.chosen,\s*enabled = enabled,\s*role = ListRows\.checkRole\(trailing\),""").containsMatchIn(row))
        for (chosen in listOf(true, false)) {
            assertEquals("a choice-page row, chosen $chosen", androidx.compose.ui.semantics.Role.RadioButton, ListRows.checkRole(RowTrailing.Check(chosen)))
        }
    }

    /** D-225 (Design 10): C2's pushed window, extracted: the currency list and the choice page both draw in it; its lines
     *  (no dim, under the system bars, the slide from the end unless remove animations) moved, not rewritten. */
    @Test
    fun the_pushed_window_is_shared() {
        val window = rows("fun PushedWindow(")
        for (s in listOf(
            "Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))",
            "window?.setDimAmount(0f)", "isAppearanceLightStatusBars = !dark", "SkiptiWindowLocals {",
        )) assertTrue("PushedWindow: $s", s in window)
        val currency = src("CurrencyChoice.kt")
        val dialog = currency.substringAfter("fun CurrencyListDialog(").substringBefore("\n}\n")
        assertTrue("the currency list draws in it", "PushedWindow(CurrencyCopy.ALL_CURRENCIES, dark, onClose)" in dialog)
        assertTrue("the choice page draws in it", "PushedWindow(" in rows("fun <T> ChoicePage("))
        for (gone in listOf("setDimAmount", "Dialog(onDismissRequest", "DialogWindowProvider")) assertFalse("CurrencyChoice.kt keeps $gone", gone in currency)
    }

    /** D-225 (Design 9): the hairline field is iOS's `HairlineFieldStyle`: a plain text field padded 10 vertically with a
     *  1 dp hairline under it, no outline. The field row (Design 1, 11, 12) is a plain field padded `rowPadV` at the 48 dp
     *  minimum, its placeholder in `textFaint`. */
    @Test
    fun the_hairline_field_twins_ios() {
        val field = rows("fun HairlineField(")
        for (s in listOf("BasicTextField(", "padding(vertical = 10.dp)", "1.dp.toPx()", "Tok.hairline(dark)", "Tok.textFaint(dark)")) {
            assertTrue("HairlineField: $s", s in field)
        }
        val ios = java.io.File("../../ios/Skipti/App/DesignTokens.swift").readText().substringAfter("struct HairlineFieldStyle").substringBefore("\n}\n")
        assertTrue("iOS pads 10 and draws 1", ".padding(.vertical, 10)" in ios && "Rectangle().fill(Tokens.hairline).frame(height: 1)" in ios)
        val row = rows("fun FieldRow(")
        for (s in listOf("BasicTextField(", "ThemeArt.m(\"rowPadV\")", ".heightIn(min = ThemeArt.m(\"touchTarget\").dp)", "Tok.textFaint(dark)", "cursorBrush = SolidColor(Tok.accent(dark))")) {
            assertTrue("FieldRow: $s", s in row)
        }
        assertFalse("no outlined field in ListRows.kt", "OutlinedTextField" in src("ListRows.kt"))
        val caption = rows("fun CardCaption(")
        assertTrue("the caption at the detail size in textDim", "Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)" in caption)
    }

    /** D-225 follow-up (R3, the re-look's prof pair): iOS's text field draws its line 1 pt in from its box at the top
     *  and the bottom, so iOS's hairline field is 10 + 1 + line + 1 + 10 (Profile's name card 199.7 pt) where Android drew
     *  10 + line + 10 (197.7 dp; the 2.0 all in the field box: field ink to hairline 14.7 against 13.3, label to field 0.7
     *  to 0.9). The field's text box takes `ListRows.FIELD_INSET` inside iOS's 10 on both edges. */
    @Test
    fun the_hairline_field_box_is_ios_text_field_box() {
        assertEquals(1f, ListRows.FIELD_INSET, 0f)
        val field = rows("fun HairlineField(")
        assertTrue("iOS's 10, then the text field's own inset",
            "Box(Modifier.padding(vertical = 10.dp).padding(vertical = ListRows.FIELD_INSET.dp)) {" in field)
    }

    /** D-225 T5 (the T3 review): the field row's padding and 48 dp minimum sit inside the field's decoration box, so a
     *  tap anywhere in the row focuses the field (they sat on the Row outside it, and only the text line took a tap). */
    @Test
    fun the_field_row_is_tappable_across_its_whole_height() {
        val row = rows("fun FieldRow(")
        val box = row.indexOf("decorationBox = { field ->")
        assertTrue("FieldRow has a decoration box", box >= 0)
        val inside = row.substring(box)
        val outside = row.substring(0, box)
        for (s in listOf(".heightIn(min = ThemeArt.m(\"touchTarget\").dp)", ".padding(vertical = ThemeArt.m(\"rowPadV\").dp)")) {
            assertTrue("inside the decoration box: $s", s in inside)
            assertFalse("outside the field: $s", s in outside)
        }
    }

    private companion object {
        // D-221: + the Settled card's "Settled" (one block, or above several) and each block's currency kicker.
        // D-223: "By group" out, "Your ledgers" and Lately's label (inside its card) in: 49 - 1 + 2.
        // D-225 T5: + People's "People" label (Security's "Careful" moved from SectionHeader to a call of its own: 0).
        // D-225 T7: 49: Add expense's "Paid by" and "Lines" labels went (rows of the split card, Design 11), New group's
        // "Settles in" became a row, and Your name gained iOS's "Your name" label (Design 12).
        // D-216: 50, Appearance's "App icon" label. D-234 (A2): 49, the label went (the header row says it).
        const val SECTION_LABELS = 50
        // D-225 T7 (A7): + the vault recovery screen's "Ledger unavailable" (iOS's micro label).
        // D-225 fix wave (M3): 25: Add expense's four hint kickers are one call over `AddSheetCopy.hint` (4 - 1).
        const val KICKERS = 25
    }
}
