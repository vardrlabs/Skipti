package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-246: motion.py 5a's printout (.drafts/d246/motion-after2.out since the fix wave, "D-246 glyph peaks"): each Futhark glyph's blurred
 *  mask's largest coverage at blur 0.18 x the size, and each layer's largest (the floors take it under the full core).
 *  The twin of iOS's `GlyphGlowGoldens` (ThemeMotionTests.swift), value for value. */
object GlyphGlowGoldens {
    val PEAKS: List<Pair<String, Double>> = listOf(
        "\u16A0" to 0.2319, "\u16A2" to 0.2203, "\u16A6" to 0.2498, "\u16A8" to 0.2454, "\u16B1" to 0.2641,
        "\u16B2" to 0.1373, "\u16B7" to 0.2101, "\u16B9" to 0.2322, "\u16BA" to 0.2657, "\u16BE" to 0.2179,
        "\u16C1" to 0.1572, "\u16C3" to 0.1145, "\u16C7" to 0.1739, "\u16C8" to 0.2081, "\u16C9" to 0.2604,
        "\u16CA" to 0.1998, "\u16CF" to 0.2157, "\u16D2" to 0.2817, "\u16D6" to 0.2572, "\u16D7" to 0.3031,
        "\u16DA" to 0.1797, "\u16DC" to 0.1961, "\u16DE" to 0.3430, "\u16DF" to 0.2247,
    )
    const val PAGE = 0.3430
    const val HERO = 0.2657
    const val FEHU = 0.2319

    /** D-246 fix wave (I1): each layer's MEASURED ink reach (x size from the box's centre, motion.py 5a "ink reach"): the
     *  receiving rune's ink wherever a glow lands on another rune (1d, 1e), where D-243's 0.30 disc stood. */
    const val INK_PAGE = 0.4569
    const val INK_HERO = 0.4463

    /** motion.py 1d's lane bound (.drafts/d246/motion-after2.out): the most glow a hero row's measured ink takes from the
     *  other rows at their lane distances, each at the top of its flare, summed (row 1, 328 wide). */
    const val HERO_LANE_GLOW = 0.011468
}

/** D-246: GlyphGlow's raster on the JVM: the bundled Vardr Runes Carved (the res/font file, the D-215 way) through
 *  java.awt, the glyph's outline filled anti-aliased, as the phone fills it through `Paint.getTextPath` on a software
 *  canvas (that half needs a device and is seen on the emulator). No image: the JVM has no Bitmap. */
object AwtGlyphRaster : GlyphGlow.Raster {
    private val face = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File("src/main/res/font/vardrrunes_carvedregular.ttf"))
    private val frc = java.awt.font.FontRenderContext(null, true, true)

    override fun metrics(glyph: String, em: Double): DoubleArray {
        val font = face.deriveFont(em.toFloat())
        val line = font.getLineMetrics(glyph, frc)
        val advance = font.createGlyphVector(frc, glyph).getGlyphMetrics(0).advanceX.toDouble()
        return doubleArrayOf(advance, line.ascent.toDouble(), line.descent.toDouble())
    }

    override fun coverage(glyph: String, em: Double, width: Int, height: Int, x: Double, baseline: Double): IntArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE)
        g.color = java.awt.Color.WHITE
        g.fill(face.deriveFont(em.toFloat()).createGlyphVector(frc, glyph).getOutline(x.toFloat(), baseline.toFloat()))
        g.dispose()
        val px = IntArray(width * height)
        image.raster.getPixels(0, 0, width, height, px)
        return px
    }

    override fun image(alpha: DoubleArray, width: Int, height: Int): androidx.compose.ui.graphics.ImageBitmap? = null

    /** The tests measure each mask's alpha (D-246 fix wave, M2: the seam the phone's raster leaves off). */
    override val keepsAlpha: Boolean get() = true
}

/** D-214: the twin of iOS ThemeMotionTests, plus parity: ThemeMotion.swift's strengths and sign list equal Kotlin's. */
class ThemeMotionTest {
    private val swift = java.io.File("../../ios/Skipti/App/ThemeMotion.swift").readText()

    @Test
    fun only_an_active_screen_with_motion_allowed_ticks() {
        assertTrue(MotionClock.shouldTick(reduceMotion = false, lowPower = false, active = true))
        assertFalse(MotionClock.shouldTick(reduceMotion = true, lowPower = false, active = true))
        assertFalse(MotionClock.shouldTick(reduceMotion = false, lowPower = true, active = true))
        assertFalse(MotionClock.shouldTick(reduceMotion = false, lowPower = false, active = false))
        assertEquals(30.0, MotionClock.FPS, 0.0)
    }

    /** D-239 (decision 137): a moment ignores battery saver; only "remove animations", Less motion or a screen not
     *  resumed stills it. The art keeps battery saver's still frame (above). */
    @Test
    fun a_moment_moves_under_power_saving_and_the_art_does_not() {
        val saving = MotionState.of(system = false, lessMotion = false, lowPower = true, resumed = true)
        assertFalse("the art pauses", saving.tick)
        assertTrue("a moment moves", saving.moment().tick)
        assertFalse("remove animations still stills a moment", MotionState.of(system = true, lessMotion = false, lowPower = true, resumed = true).moment().tick)
        assertFalse("Less motion still stills a moment", MotionState.of(system = false, lessMotion = true, lowPower = false, resumed = true).moment().tick)
        assertFalse("a screen not resumed", MotionState.of(system = false, lessMotion = false, lowPower = false, resumed = false).moment().tick)
    }

    /** D-239 review (I1): a screen's check runs until it lands; Norse's ring turns on only while the art ticks. */
    @Test
    fun the_done_ring_turns_only_while_the_art_does() {
        val landed = CheckMotion.POP_AT + CheckMotion.POP_DURATION
        assertEquals(Double.POSITIVE_INFINITY, DoneCheck.runFor(AppTheme.NORSE, artTicks = true), 0.0)
        assertEquals("battery saver: the ring stops", landed, DoneCheck.runFor(AppTheme.NORSE, artTicks = false), 0.0)
        assertEquals(landed, DoneCheck.runFor(AppTheme.AURORA, artTicks = true), 0.0)
        assertEquals(landed, DoneCheck.runFor(AppTheme.VARDR, artTicks = true), 0.0)
        assertEquals(1.2, landed, 1e-9)
    }

    /** D-239 (decision 137): only the art reads battery saver. Any other read of `.lowPower` in the shell would still a
     *  moment again (the founder's phones froze every animation under power saving). The twin of iOS's
     *  testOnlyTheArtReadsPowerSaving. */
    @Test
    fun only_the_art_reads_power_saving() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val readers = dir.listFiles()!!.filter { it.name.endsWith(".kt") }
            .associate { f -> f.name to Regex("""\blowPower\b""").findAll(f.readText()).count() }
            .filter { it.value > 0 }
        assertEquals(setOf("ThemeMotion.kt"), readers.keys)
        // D-239 review (M2): the count too, so a second read inside ThemeMotion.kt (beside MotionState) fails here; and
        // the system's flag is read once, by `powerSave`, so no read can hide under another name.
        // D-243 (amended on purpose, 16 -> 17): the Norse hero's art layer reads the art's rule of stillness
        // (`RuneLayer.isStill(motion.reduce, motion.lowPower)`, as the page's runes do).
        assertEquals("ThemeMotion.kt's lowPower", 17, readers["ThemeMotion.kt"])
        val direct = dir.listFiles()!!.filter { it.name.endsWith(".kt") }
            .associate { f -> f.name to Regex("""\bisPowerSaveMode\b|ACTION_POWER_SAVE_MODE_CHANGED""").findAll(f.readText()).count() }
            .filter { it.value > 0 }
        assertEquals(mapOf("ThemeMotion.kt" to 2), direct)
        val motion = dir.resolve("ThemeMotion.kt").readText()
        assertTrue("the art's rule", "RuneLayer.isStill(motion.reduce, motion.lowPower)" in motion)
        assertTrue("a moment drops it", "fun moment(): MotionState = copy(lowPower = false)" in motion)
        val done = dir.resolve("DoneMoments.kt").readText()
        assertEquals("the host reads a moment's state", 1, Regex("""rememberMotionState\(\)\.moment\(\)""").findAll(done).count())
        assertTrue("the check reads both rules", "val art = rememberMotionState()\n    val motion = art.moment()" in done)
        assertTrue("the overlay's play is a moment", "val moment = motion.moment()" in done)
        // D-239 review (I1): the check's clock ends (the ring turns on only while the art ticks), and the check draws
        // from that clock, never from an art canvas whose loop runs while the screen shows.
        assertTrue("the ring follows the art", "LaunchedEffect(own, art.tick) {\n            own.run(DoneCheck.runFor(theme, artTicks = art.tick))" in done)
        assertFalse("no art canvas in the done moments", "artCanvas(" in done)
        assertTrue("drawn from the moment", "val pose = CheckMotion.pose(p.elapsed(CheckMotion.STILL_ELAPSED))" in done)
        assertTrue("the burst's canvas is a moment",
            "artCanvas(still = 0.0, state = motion.moment())" in dir.resolve("Settled.kt").readText())
    }

    @Test
    fun the_curtains_sway_as_the_look_book_does() {
        val want = listOf(
            Triple(0, 0.0, doubleArrayOf(-0.184, -0.12, -16.0, 1.0, 1.0)), Triple(0, 3.25, doubleArrayOf(-0.121, -0.113, -5.0, 1.025, 1.075)),
            Triple(0, 6.5, doubleArrayOf(-0.058, -0.106, 6.0, 1.05, 1.15)), Triple(0, 13.0, doubleArrayOf(-0.002, -0.134, -8.0, 0.95, 1.0)),
            Triple(0, 19.5, doubleArrayOf(-0.058, -0.106, 6.0, 1.05, 1.15)),
            Triple(1, 0.0, doubleArrayOf(0.316966, -0.110705, 1.628981, 1.040066, 1.120198)),
            Triple(2, 0.0, doubleArrayOf(0.537966, -0.114517, -3.434537, 1.028558, 1.085674)),
            // Later times, where a period or direction bug would show past the delay (motion.py's curtain_pose).
            Triple(1, 9.5, doubleArrayOf(0.393806, -0.129863, -6.95152, 0.957489, 1.011234)),
            Triple(2, 7.25, doubleArrayOf(0.64547, -0.128732, -7.367424, 0.954518, 1.006778)),
        )
        for ((i, t, w) in want) {
            val p = Curtains.pose(i, t)
            doubleArrayOf(p.x, p.y, p.skewDegrees, p.sx, p.sy).forEachIndexed { k, v -> assertEquals("curtain $i at $t", w[k], v, 1e-5) }
        }
        assertEquals(listOf(13.0, 17.0, 11.0), Curtains.SPECS.map { it.period })
    }

    /** The Look Book's curtain and sky data, pinned so a stray edit is seen (twin of iOS). */
    @Test
    fun the_curtain_data_is_the_look_books() {
        assertEquals(0.0, Curtains.STILL, 0.0)
        assertEquals(0.70, Curtains.WIDTH, 0.0)
        assertEquals(-0.12, Curtains.TOP, 0.0)
        assertEquals(listOf(-0.10, 0.30, 0.55), Curtains.SPECS.map { it.left })
        assertEquals(listOf(0.70, 0.58, 0.48), Curtains.SPECS.map { it.height })
        assertEquals(listOf(0.0, -6.0, -3.0), Curtains.SPECS.map { it.delay })
        assertEquals(listOf(0.35, 0.35, 0.35), Curtains.SPECS.map { it.lightOpacity })
        assertEquals(listOf(0.80, 0.62, 0.50), Curtains.SPECS.map { it.nightOpacity })
        fun flat(stops: List<CurtainStop>) = stops.flatMap { listOf(it.r.toDouble(), it.g.toDouble(), it.b.toDouble(), it.alpha, it.at) }
        val pgc = listOf(214.0, 92.0, 170.0, 0.0, 0.0, 214.0, 92.0, 170.0, 0.42, 0.12, 69.0, 230.0, 176.0, 0.85, 0.34,
            31.0, 182.0, 201.0, 0.35, 0.64, 31.0, 182.0, 201.0, 0.0, 1.0)
        assertEquals(pgc, flat(Curtains.SPECS[0].stops))
        assertEquals(pgc, flat(Curtains.SPECS[1].stops))
        assertEquals(
            listOf(69.0, 230.0, 176.0, 0.0, 0.0, 69.0, 230.0, 176.0, 0.70, 0.30, 31.0, 182.0, 201.0, 0.30, 0.70, 31.0, 182.0, 201.0, 0.0, 1.0),
            flat(Curtains.SPECS[2].stops),
        )
        assertEquals(
            listOf(listOf(-0.12, 0.0, -16.0, 1.0, 1.0), listOf(0.06, 0.02, 6.0, 1.05, 1.15), listOf(0.14, -0.02, -8.0, 0.95, 1.0)),
            Curtains.KEYS.map { it.toList() },
        )
        assertEquals(
            listOf(
                listOf(0.12, 0.18, 1.0, 0.9), listOf(0.33, 0.09, 1.0, 0.7), listOf(0.61, 0.14, 1.2, 0.9), listOf(0.82, 0.26, 1.0, 0.6),
                listOf(0.47, 0.31, 1.0, 0.5), listOf(0.90, 0.08, 1.0, 0.8), listOf(0.24, 0.44, 1.0, 0.4), listOf(0.70, 0.40, 1.0, 0.5),
            ),
            Stars.ALL.map { listOf(it.x, it.y, it.radius, it.alpha) },
        )
        assertEquals(11.0, Curtains.STREAK_PITCH, 0.0)
        assertEquals(listOf(0.0 to 2.0, 6.0 to 1.0), Curtains.STREAK_BARS)
        assertEquals(0.60, Curtains.STREAK_MEAN, 1e-12)
    }

    /** F4: the arched hem, the Look Book's `border-radius: 0 0 50% 50% / 0 0 30% 30%`: square above 0.70 of the
     *  height, two quarter ellipses below; the drawn cubics lie on that ellipse (twin of iOS). */
    @Test
    fun the_curtains_hang_in_an_arched_hem() {
        assertEquals(0.70, Curtains.HEM_TOP, 0.0)
        assertTrue("square above the hem", Curtains.insideHem(0.01, 0.69))
        assertTrue("the arch's lowest point is the bottom centre", Curtains.insideHem(0.5, 0.99))
        assertFalse("a lower corner is cut away", Curtains.insideHem(0.02, 0.98))
        assertFalse("the other lower corner too", Curtains.insideHem(0.98, 0.95))
        assertFalse(Curtains.insideHem(0.5, 1.01))
        assertTrue("just inside the ellipse", Curtains.insideHem(0.5 + 0.5 * kotlin.math.cos(0.6) * 0.999, 0.70 + 0.30 * kotlin.math.sin(0.6) * 0.999))
        assertFalse("just outside it", Curtains.insideHem(0.5 + 0.5 * kotlin.math.cos(0.6) * 1.001, 0.70 + 0.30 * kotlin.math.sin(0.6) * 1.001))
        val w = 281.4
        val h = 611.8
        val curves = Curtains.hemCurves(w, h)
        assertEquals(2, curves.size)
        var fx = w
        var fy = h * 0.70
        for (k in curves) {
            // The cubic's midpoint sits on the ellipse (radius error under 0.1%).
            fun mid(a: Double, b: Double, c: Double, d: Double) = (a + 3 * b + 3 * c + d) / 8
            val mx = mid(fx, k[0], k[2], k[4])
            val my = mid(fy, k[1], k[3], k[5])
            val r = kotlin.math.sqrt(((mx - w / 2) / (w / 2)).let { it * it } + ((my - h * 0.70) / (h * 0.30)).let { it * it })
            assertEquals(1.0, r, 1e-3)
            fx = k[4]; fy = k[5]
        }
        assertEquals(0.0, fx, 1e-9)
        assertEquals(h * 0.70, fy, 1e-9)
    }

    @Test
    fun every_curtain_stays_in_its_band() {
        for (i in 0..2) for (step in 0..340) {
            val p = Curtains.pose(i, step * 0.1)
            assertTrue("curtain $i top", p.y <= -0.10)
            assertTrue("curtain $i left", p.x + p.width > 0)
            assertTrue("curtain $i right", p.x < 1)
        }
    }

    @Test
    fun the_runes_rise_as_the_look_book_does() {
        val w = 402f
        val h = 874f
        data class W(val i: Int, val t: Double, val glyph: String, val silver: Boolean, val v: DoubleArray)
        val want = listOf(
            // D-243 stage 3: x moved with the lanes (8.04, 156.78, 156.78, 12.06, 20.1 before; motion-lanes.out section 3)
            W(0, 0.0, "\u16A0", true, doubleArrayOf(32.696, 904.0, 14.0, 0.0, 0.0)),
            W(1, 0.0, "\u16B9", false, doubleArrayOf(205.288, 459.875, 19.0, 6.6957, 0.140739)),
            W(1, 11.5, "\u16B9", false, doubleArrayOf(205.288, -4.4375, 19.0, 13.6957, 0.014493)),
            W(5, 30.0, "\u16C3", false, doubleArrayOf(57.352, 500.25, 25.0, 6.087, 0.145503)),
            W(15, 7.0, "\u16BE", true, doubleArrayOf(82.008, 258.0, 19.0, 9.7391, 0.116915)),
        )
        for (x in want) {
            val p = RuneDrift.pose(x.i, x.t, w, h)
            assertEquals(x.glyph, p.glyph)
            assertEquals(x.silver, p.silver)
            doubleArrayOf(p.x, p.y, p.size, p.rotationDegrees, p.opacity).forEachIndexed { k, v -> assertEquals("rune ${x.i} at ${x.t}", x.v[k], v, 1e-3) }
        }
        val s = RuneDrift.still(1, w, h)
        doubleArrayOf(s.x, s.y, s.rotationDegrees, s.opacity).zip(doubleArrayOf(205.288, 515.66, 0.0, 0.10)).forEach { (a, b) -> assertEquals(b, a, 1e-3) }
        assertEquals(16, RuneDrift.COUNT)
    }

    /** D-243 stage 3 (the founder, 2026-10-20: "Give each rune its own lane"; .drafts/d214/motion.py 1e and section 6,
     *  retyped from .drafts/d243/motion-lanes.out): one page rune per lane, evenly spread from 2% to 94% of the width; x
     *  on 402 and the still layout are motion.py's (the still y did not move); on a 360 page no two runes' inks (0.30 x
     *  size, motion.py's INK) can meet: every two lanes are at least 0.30 x (s_i + s_j) apart. Negative: two lanes 1%
     *  apart fail the rule. Parity: the table is ThemeMotion.swift's `RuneDrift.lanes`. Twin of iOS's
     *  testEachRuneHasItsOwnLane. */
    @Test
    fun each_rune_has_its_own_lane() {
        assertEquals(listOf(1, 8, 14, 5, 11, 2, 7, 15, 6, 10, 0, 9, 13, 4, 12, 3), RuneDrift.LANES.toList())
        assertEquals("one rune per lane", (0 until RuneDrift.COUNT).toList(), RuneDrift.LANES.sorted())
        val ios = after("enum RuneDrift {").substringAfter("static let lanes = [").substringBefore("]")
        assertEquals("ThemeMotion.swift's lanes", RuneDrift.LANES.toList(), numbers(ios).map { it.toInt() })
        val x402 = doubleArrayOf(32.696, 205.288, 353.224, 131.32, 279.256, 57.352, 180.632, 377.88, 155.976, 254.6, 8.04, 229.944, 328.568, 106.664, 303.912, 82.008)
        val still = listOf(32.696 to 874.0, 205.288 to 515.66, 353.224 to 157.32, 131.32 to 585.58, 279.256 to 227.24, 57.352 to 655.5, 180.632 to 297.16, 377.88 to 725.42, 155.976 to 367.08, 254.6 to 795.34, 8.04 to 437.0, 229.944 to 865.26, 328.568 to 506.92, 106.664 to 148.58, 303.912 to 576.84, 82.008 to 218.5)
        for (i in 0 until RuneDrift.COUNT) {
            assertEquals("rune $i x", x402[i], RuneDrift.pose(i, 0.0, 402f, 874f).x, 1e-4)
            assertEquals("rune $i keeps its lane", x402[i], RuneDrift.pose(i, 17.3, 402f, 874f).x, 1e-4)
            val s = RuneDrift.still(i, 402f, 874f)
            assertEquals("still $i x", still[i].first, s.x, 1e-4)
            assertEquals("still $i y", still[i].second, s.y, 1e-4)
        }
        fun inkApart(xs: DoubleArray, sizes: DoubleArray): Boolean {
            for (i in xs.indices) for (j in i + 1 until xs.size) if (Math.abs(xs[i] - xs[j]) < 0.30 * (sizes[i] + sizes[j])) return false
            return true
        }
        val xs = DoubleArray(RuneDrift.COUNT) { RuneDrift.pose(it, 0.0, 360f, 740f).x }
        val sizes = DoubleArray(RuneDrift.COUNT) { RuneDrift.pose(it, 0.0, 360f, 740f).size }
        assertTrue("no two runes' inks meet on a 360 page", inkApart(xs, sizes))
        val sorted = xs.sorted()
        assertEquals("the first lane at 2%", 0.02 * 360, sorted[0], 1e-9)
        assertEquals("the last lane at 94%", 0.94 * 360, sorted[15], 1e-9)
        sorted.zipWithNext().forEach { (a, b) -> assertEquals("lanes evenly spread", 360 * 0.92 / 15, b - a, 1e-9) }
        val crowded = xs.copyOf().also { it[1] = it[0] + 0.01 * 360 }
        assertFalse("two lanes 1% apart fail the ink rule", inkApart(crowded, sizes))
    }

    /** Over a full cycle each rune stays inside the page: the Look Book anchors x at the glyph's left edge, so x >= 0
     *  and x + size <= width keep its em box inside (the shells centre the glyph on x, half a glyph off the Look Book,
     *  which BUILD-LOG D-214 records); it turns at most 14 degrees and never passes 0.18. Twin of iOS. */
    @Test
    fun every_rune_stays_in_its_box() {
        val w = 402f
        val h = 874f
        for (i in 0 until RuneDrift.COUNT) for (step in 0..300) {
            val p = RuneDrift.pose(i, step * 0.1, w, h)
            assertTrue("rune $i x", p.x >= 0.0 && p.x + p.size <= w.toDouble())
            assertTrue("rune $i rotation", p.rotationDegrees in 0.0..14.0)
            assertTrue("rune $i opacity", p.opacity in 0.0..(0.18 + 1e-9))
            assertTrue("rune $i y", p.y <= h + 30 && p.y >= h + 30 - h * 680.0 / 640 - 1e-6)
        }
    }

    @Test
    fun the_currency_field_is_the_look_books_seeded_field() {
        val first = CurrencyField.draws(3)
        assertEquals(0.000054784, first[0], 1e-9)
        assertEquals(0.920764517, first[1], 1e-9)
        assertEquals(0.289237255, first[2], 1e-9)
        fun floater(g: String) = CurrencyField.FLOATERS.firstOrNull { it.glyph == g } ?: error("no floater $g")
        val dollar = floater("$")
        val rupee = floater("\u20B9")
        val euro = floater("\u20AC")
        assertEquals(0.332343, dollar.sx, 1e-6); assertEquals(0.237197, dollar.sy, 1e-6); assertEquals(2.069069, dollar.ph, 1e-6); assertEquals(0.127537, dollar.rot, 1e-6)
        assertTrue(dollar.cyan)
        assertEquals(0.243558, rupee.sx, 1e-6); assertEquals(4.385821, rupee.ph, 1e-6); assertTrue(rupee.cyan); assertEquals(40, rupee.size)
        assertEquals(0.380792, euro.sx, 1e-6); assertEquals(0.205998, euro.rot, 1e-6)
        val poses = listOf(
            Triple(dollar, 0.0, doubleArrayOf(0.64196, 0.635017, 0.232947, 0.648208, 36.0)),
            Triple(dollar, 4.0, doubleArrayOf(0.613649, 0.636049, 0.112282, 0.606685, 36.0)),
            Triple(dollar, 10.0, doubleArrayOf(0.600562, 0.697115, 0.01509, 0.587491, 36.0)),
            Triple(rupee, 0.0, doubleArrayOf(0.776321, 0.361779, -0.021703, 0.66, 48.0)),
            Triple(rupee, 10.0, doubleArrayOf(0.812815, 0.291563, 0.199043, 0.66, 48.0)),
        )
        for ((f, t, w) in poses) {
            val p = CurrencyField.pose(f, t)
            doubleArrayOf(p.x, p.y, p.rotation, p.alpha, p.size).forEachIndexed { k, v -> assertEquals("${f.glyph} at $t", w[k], v, 1e-5) }
        }
        assertEquals(
            listOf("$", "\u20B9", "\u20AC", "\u00A3", "\u00A5", "\u20A9", "\u20BD", "\u0E3F", "\u20BA", "\u20A6", "\u20B1",
                "\u20AA", "\u20AB", "\u20B4", "\u5143", "R$", "kr", "\u00A2", "\u20B8", "A$", "C$", "z\u0142"),
            CurrencyField.SIGNS,
        )
        assertEquals(4.0, CurrencyField.STILL, 0.0)
    }

    @Test
    fun every_sign_stays_inside_the_hero_and_the_majors_stay_right() {
        val majors = setOf("$", "\u20B9", "\u20AC", "\u00A3", "\u00A5")
        for (f in CurrencyField.FLOATERS) for (step in 0..400) {
            val p = CurrencyField.pose(f, step * 0.5)
            assertTrue("${f.glyph} leaves the hero", p.x > 0 && p.x < 1 && p.y > 0 && p.y < 1)
            if (f.glyph in majors) assertTrue("${f.glyph} drifts left", p.x >= 0.55)
        }
    }

    /** The strengths motion.py prints (section 1b, on 8-bit pixels since the founder's rule of 2026-10-15). */
    @Test
    fun the_strengths_are_the_measured_ones() {
        assertEquals(
            mapOf(
                "auroraDarkPageCurtains" to 0.26, "auroraDarkPageStars" to 0.16, "auroraLightPageCurtains" to 1.0,
                "auroraHeroCurtains" to 1.0, "auroraHeroStars" to 1.0, "norsePageRunes" to 0.79, "norseHero" to 1.0,
                "vardrLightHeroSigns" to 0.60, "vardrDarkHeroSigns" to 0.54,
                // D-226 (art.py section 48): Norse's page glows at the Look Book's own strength.
                "norsePageGlow" to 1.0,
                // D-243 (motion.py section 6): the hero runes' rest (0.20 x 0.142 / 0.19 / 0.18).
                "norseHeroRunes" to 0.83,
            ),
            Strength.METRICS,
        )
    }

    /** D-226 (L34, .drafts/d226/art.py section 48): Norse's two page glows equal iOS's `NorseGlow.specs`, read out of
     *  ThemeMotion.swift (as the curtains' specs are): the numbers in order, and each colour resolved through the name
     *  the Swift table gives it (`ArtDraw.runeGoldHex`, `ThemeArt.hex["norseSilver"]`). Pinned to art.py too. */
    @Test
    fun norse_glow_matches_ios() {
        val block = after("enum NorseGlow {").substringAfter("static let specs: [Spec] = [").substringBefore("\n    ]")
        val rows = Regex("""Spec\(hex: ([^,]+), alpha: ([0-9.]+), radiusX: ([0-9.]+), radiusY: ([0-9.]+), centreX: ([0-9.]+), centreY: ([0-9.]+), clearAt: ([0-9.]+)\)""")
            .findAll(block).toList()
        assertEquals(2, rows.size)
        assertEquals(
            NorseGlow.SPECS.map { listOf(it.alpha, it.radiusX, it.radiusY, it.centreX, it.centreY, it.clearAt) },
            rows.map { m -> m.groupValues.drop(2).map { it.toDouble() } },
        )
        val art = java.io.File("../../ios/Skipti/App/ThemeArt.swift").readText()
        fun resolve(expr: String): String = when (expr) {
            "ArtDraw.runeGoldHex" -> Regex("""static let runeGoldHex = "(#[0-9A-F]{6})"""").find(swift)!!.groupValues[1]
            "ThemeArt.hex[\"norseSilver\"]!" -> Regex(""""norseSilver": "(#[0-9A-F]{6})"""").find(art)!!.groupValues[1]
            else -> error("an unknown colour in iOS's NorseGlow.specs: $expr")
        }
        fun hex(c: androidx.compose.ui.graphics.Color) =
            "#%02X%02X%02X".format(Math.round(c.red * 255), Math.round(c.green * 255), Math.round(c.blue * 255))
        assertEquals(NorseGlow.SPECS.map { hex(it.color) }, rows.map { resolve(it.groupValues[1]) })
        // art.py 48's table (D-242): gold #DFB043 at 0.09 in 0.90 W x 0.60 H at (0.5, 0.0), clear at 0.70; silver #9AA2AE at 0.07
        // in 1.20 W x 0.80 H at (0.5, 1.20), clear at 0.60.
        assertEquals(listOf("#DFB043", "#9AA2AE"), NorseGlow.SPECS.map { hex(it.color) })
        assertEquals(listOf(0.09, 0.07), NorseGlow.SPECS.map { it.alpha })
        assertEquals(listOf(0.70, 0.60), NorseGlow.SPECS.map { it.clearAt })
    }

    @Test
    fun the_bolt_turns_only_when_pressed_and_only_the_bolt() {
        assertEquals(30f, AddButtonMotion.turn(AddButtonArt.BOLT, pressed = true, reduceMotion = false), 0f)
        assertEquals(0f, AddButtonMotion.turn(AddButtonArt.BOLT, pressed = false, reduceMotion = false), 0f)
        assertEquals(0f, AddButtonMotion.turn(AddButtonArt.BOLT, pressed = true, reduceMotion = true), 0f)
        assertEquals(0f, AddButtonMotion.turn(AddButtonArt.DIAMOND, pressed = true, reduceMotion = false), 0f)
        assertEquals(0f, AddButtonMotion.turn(AddButtonArt.CIRCLE, pressed = true, reduceMotion = false), 0f)
    }

    /** D-221 (motion.py part 5): the burst's easing and poses, re-derived in Python from the Look Book's `celebrate`. */
    @Test
    fun the_burst_matches_the_goldens() {
        val easing = listOf(0.1 to 0.332602, 0.25 to 0.676225, 0.5 to 0.909972, 0.75 to 0.983712, 0.9 to 0.997765)
        for ((u, y) in easing) assertEquals("ease($u)", y, SettledBurst.ease(u), 1e-5)
        assertEquals(0.0, SettledBurst.ease(0.0), 0.0)
        assertEquals(1.0, SettledBurst.ease(1.0), 0.0)
        val poses = listOf(
            Triple(0, 0.0, listOf(0.0, 0.0, 0.4, 0.0)),
            Triple(0, 0.3, listOf(102.4933, 2.2781, 0.984133, 0.920664)),
            Triple(0, 0.75, listOf(120.5984, 22.9696, 0.840012, 0.200062)),
            Triple(0, 1.2, listOf(125.0735, 28.084, 0.80439, 0.02195)),
            Triple(0, 1.49, listOf(125.6245, 28.7137, 0.800004, 2e-05)),
            Triple(7, 0.5, listOf(-19.9155, 173.9785, 0.931106, 0.655532)),
            Triple(13, 0.9, listOf(-216.0501, 24.55, 0.829144, 0.145721)),
            Triple(25, 1.0, listOf(119.5441, -3.6279, 0.819883, 0.099414)),
            Triple(3, 1.85, listOf(166.5731, 176.2784, 0.800003, 1.3e-05)),
        )
        for ((i, t, want) in poses) {
            val p = SettledBurst.pose(i, t, 402.0)
            assertTrue("particle $i at $t is drawn", p != null)
            assertEquals("particle $i at $t", want, listOf(p!!.dx, p.dy, p.scale, p.opacity).zip(want) { a, b -> if (kotlin.math.abs(a - b) <= 1e-3) b else a })
        }
        assertEquals("particle 3 is done at 1.86 s", null, SettledBurst.pose(3, 1.86, 402.0))
        assertEquals(1.86, SettledBurst.duration(3), 1e-9)
        assertEquals(1.5, SettledBurst.duration(4), 1e-9)
    }

    /** D-221: the celebration's timeline (motion.py part 5) and its particle sizes. */
    @Test
    fun celebration_timing() {
        assertEquals(3.6, CelebrationTiming.TOTAL, 0.0)
        assertEquals(0.22, CelebrationTiming.DIM_TO, 0.0)
        assertEquals(0.3, CelebrationTiming.DIM_IN, 0.0)
        assertEquals(0.4, CelebrationTiming.MESSAGE_IN, 0.0)
        assertEquals(10.0, CelebrationTiming.MESSAGE_RISE, 0.0)
        assertEquals(0.42, CelebrationTiming.MESSAGE_TOP, 0.0)
        assertEquals(26, SettledBurst.COUNT)
        assertEquals(listOf(0.5, 0.46), listOf(SettledBurst.ORIGIN_X, SettledBurst.ORIGIN_Y))
        assertEquals(listOf(8.0, 10.0, 22.0, 4.0), listOf(SettledBurst.AURORA_DOT, SettledBurst.AURORA_GLOW, SettledBurst.NORSE_RUNE, SettledBurst.VARDR_SQUARE))
    }

    // ---- D-243: livelier Norse art (goldens retyped from .drafts/d243/motion-after.out, never read from the code) ----

    /** D-243 (plan test 1): a rune flares now and then: 0 at rest, eased up from 0.62 of its cycle to 1 at 0.76, eased
     *  back to 0 at 0.90; never outside 0..1; only the top reaches 1. The twin of iOS's testTheRunesFlareAsTheMockUpDoes. */
    @Test
    fun the_runes_flare_as_the_mock_up_does() {
        assertEquals(listOf(7, 12, 10, 8, 13, 11, 9, 7, 12, 10, 8, 13, 11, 9, 7, 12).map { it.toDouble() }, (0 until 16).map { RuneGlow.cycle(it) })
        assertEquals(listOf(0, 3, 6, 9, 1, 4, 7, 10, 2, 5, 8, 0, 3, 6, 9, 1).map { it.toDouble() }, (0 until 16).map { RuneGlow.lead(it) })
        assertEquals(listOf(0.62, 0.76, 0.90), listOf(RuneGlow.RISE, RuneGlow.TOP, RuneGlow.FALL))
        assertEquals(listOf(0.42, 0.0, 0.58, 1.0), RuneGlow.EASE)
        val shape = listOf(0.0 to 0.0, 0.61 to 0.0, 0.62 to 0.0, 0.65 to 0.094109, 0.69 to 0.5, 0.76 to 1.0, 0.83 to 0.5,
            0.87 to 0.094109, 0.899 to 0.000097, 0.90 to 0.0, 0.95 to 0.0)
        for ((q, g) in shape) assertEquals("shape at $q", g, RuneGlow.shape(q), 1e-6)
        assertTrue("it still glows just before the fall", RuneGlow.shape(0.899) > 0.0)
        assertEquals("and is at rest from the fall on", 0.0, RuneGlow.shape(0.90), 0.0)
        assertEquals(1.0, RuneGlow.shape(0.76), 0.0)
        data class F(val i: Int, val t: Double, val g: Double)
        val flare = listOf(
            F(0, 4.27, 0.0), F(0, 4.34, 0.0), F(0, 4.83, 0.5), F(0, 5.32, 1.0), F(0, 5.81, 0.5), F(0, 6.293, 0.000097),
            F(0, 6.3, 0.0), F(5, 3.59, 0.5), F(15, 7.28, 0.5), F(0, 813_000_000.25, 0.0), F(0, 813_000_003.9, 0.621569),
            F(3, 100.0, 0.002449), F(11, 12.5, 0.0),
        )
        for (f in flare) assertEquals("rune ${f.i} at ${f.t}", f.g, RuneGlow.flare(f.i, f.t), 1e-6)
        var tops = 0
        for (i in 0 until 16) for (step in 0 until 1200) {
            val g = RuneGlow.flare(i, step * 0.05)
            assertTrue("rune $i flare in 0..1", g in 0.0..1.0)
            if (g == 1.0) tops++
        }
        for (step in 0 until 1000) {
            val q = step / 1000.0
            if (q < 0.62 || q >= 0.90) assertEquals("at rest at $q", 0.0, RuneGlow.shape(q), 0.0)
            if (q != 0.76) assertTrue("only the top is 1 ($q)", RuneGlow.shape(q) < 1.0)
        }
        assertTrue(tops > 0)
    }

    /** D-243 (plan test 2): the glow's alphas; the page's core never passes 0.19, the hero's never 0.20; still: 0.079 and
     *  0.083, no glow, never a flare (the negative). D-246 (amended on purpose; D-243's halo was 0.05 on both layers, a
     *  disc of 0.8 x size): the glow is the blurred glyph's alpha (motion.py 5a), 0.120 on the page (D-246 fix wave, I1:
     *  0.145 before) and 0.345 on the hero at the top of a flare, in proportion below it, blurred by 0.18 x the size, seen
     *  to 0.7899 (0.8043 before) and 0.8567 x the size.
     *  The twin of iOS's testTheGlowAlphasAreTheMeasuredOnes, row for row. */
    @Test
    fun the_glow_alphas_are_the_measured_ones() {
        assertEquals(listOf(0.19, 0.20), listOf(RuneGlow.PAGE_FLARE, RuneGlow.HERO_FLARE))
        assertEquals(listOf(0.18, 0.120, 0.345, 0.7899, 0.8567),
            listOf(RuneGlow.BLUR, RuneGlow.PAGE_GLOW, RuneGlow.HERO_GLOW, RuneGlow.PAGE_REACH, RuneGlow.HERO_REACH))
        val page = Strength.METRICS.getValue("norsePageRunes")
        val hero = Strength.METRICS.getValue("norseHeroRunes")
        val want = listOf( // strength, top, glowTop, opacity, flare, core, glow
            doubleArrayOf(0.79, 0.19, 0.120, 0.18, 0.0, 0.142200, 0.089811), doubleArrayOf(0.79, 0.19, 0.120, 0.18, 0.5, 0.166100, 0.104905),
            doubleArrayOf(0.79, 0.19, 0.120, 0.18, 1.0, 0.19, 0.120), doubleArrayOf(0.79, 0.19, 0.120, 0.09, 0.5, 0.083050, 0.052453),
            doubleArrayOf(0.83, 0.20, 0.345, 0.18, 0.0, 0.149400, 0.257715), doubleArrayOf(0.83, 0.20, 0.345, 0.18, 0.5, 0.174700, 0.301357),
            doubleArrayOf(0.83, 0.20, 0.345, 0.18, 1.0, 0.20, 0.345), doubleArrayOf(0.83, 0.20, 0.345, 0.09, 0.5, 0.087350, 0.150679),
        )
        for (w in want) {
            val a = RuneGlow.alphas(opacity = w[3], strength = w[0], top = w[1], flare = w[4], glowTop = w[2], still = false)
            assertEquals("core at ${w.toList()}", w[5], a.core, 1e-6)
            assertEquals("glow at ${w.toList()}", w[6], a.glow, 1e-6)
            assertEquals(a.core, RuneGlow.core(w[3], w[0], w[1], w[4], false), 0.0)
            assertEquals(a.glow, RuneGlow.glow(a.core, w[1], w[2], false), 0.0)
        }
        for ((k, still) in listOf(page to 0.079, hero to 0.083)) {
            val a = RuneGlow.alphas(opacity = 0.10, strength = k, top = RuneGlow.PAGE_FLARE, flare = 1.0, glowTop = RuneGlow.PAGE_GLOW, still = true)
            assertEquals("the still core at $k", still, a.core, 1e-9)
            assertEquals("no glow when still", 0.0, a.glow, 0.0)
        }
        var pageCore = 0.0
        var pageGlow = 0.0
        for (i in 0 until RuneDrift.COUNT) for (step in 0 until 600 * 30 / 7) {
            val t = step * 7 / 30.0
            val a = RuneGlow.alphas(RuneDrift.pose(i, t, 402f, 874f).opacity, page, RuneGlow.PAGE_FLARE, RuneGlow.flare(i, t), RuneGlow.PAGE_GLOW, false)
            pageCore = kotlin.math.max(pageCore, a.core)
            pageGlow = kotlin.math.max(pageGlow, a.glow)
            val s = RuneGlow.alphas(RuneDrift.still(i, 402f, 874f).opacity, page, RuneGlow.PAGE_FLARE, RuneGlow.flare(i, t), RuneGlow.PAGE_GLOW, true)
            assertEquals("a still rune never flares", 0.10 * page, s.core, 1e-12)
            assertEquals("a still rune has no glow", 0.0, s.glow, 0.0)
        }
        assertTrue(pageCore <= 0.19 + 1e-12)
        assertTrue(pageGlow <= 0.120 + 1e-12)
        assertTrue("the page runes do flare", pageCore > 0.185)
        var heroCore = 0.0
        for (i in 0 until HeroRunes.COUNT) for (step in 0 until 600 * 10) {
            val t = step / 10.0
            val a = RuneGlow.alphas(HeroRunes.pose(i, t, 343f, 260f).opacity, hero, RuneGlow.HERO_FLARE, HeroRunes.flare(i, t), RuneGlow.HERO_GLOW, false)
            heroCore = kotlin.math.max(heroCore, a.core)
            assertTrue(a.glow <= 0.345 + 1e-12)
        }
        assertTrue(heroCore <= 0.20 + 1e-12)
        assertTrue("the hero runes do flare", heroCore > 0.19)
    }

    /** D-243 (plan test 3): the hero's own runes, in lanes left of the ᚠ; their poses and still layout; on the narrowest
     *  hero (328) each rune's ink (0.30 x size) inside the inner frame's interior and its em box inside the hero. D-246
     *  fix wave (I1, M4; amended on purpose: "no glow on another lane's ink" took the receiving ink as D-243's 0.30 disc,
     *  which undercounts the measured ink, 0.4463 x size): with the measured ink no two rows' inks meet at any height,
     *  and the glow a row's ink takes from the others at their lanes, each at its flare's top, is motion.py 1d's lane
     *  bound (0.011468), which the floors hold (AppearanceTest). The twin of iOS's testTheHeroRunesTable. */
    @Test
    fun the_hero_runes_table() {
        assertEquals(
            listOf(
                HeroRuneSpec("ᛃ", false, 0.050, 25.0, 23.0, 3.0, 11.0, 4.0, 0.75),
                HeroRuneSpec("ᛜ", true, 0.135, 15.0, 23.0, 7.0, 8.0, 9.0, 0.67),
                HeroRuneSpec("ᚺ", false, 0.220, 26.0, 16.0, 10.0, 12.0, 2.0, 0.42),
                HeroRuneSpec("ᛖ", true, 0.305, 16.0, 16.0, 14.0, 9.0, 7.0, 0.34),
                HeroRuneSpec("ᚱ", false, 0.390, 20.0, 16.0, 18.0, 13.0, 1.0, 0.26),
                HeroRuneSpec("ᛊ", true, 0.475, 17.0, 23.0, 21.0, 10.0, 5.0, 0.91),
                HeroRuneSpec("ᚢ", false, 0.560, 21.0, 23.0, 25.0, 7.0, 10.0, 0.83),
            ),
            HeroRunes.SPECS,
        )
        assertEquals(7, HeroRunes.COUNT)
        data class W(val i: Int, val t: Double, val v: DoubleArray)
        val want = listOf( // x, y, size, rotation, opacity, flare on 343 x 260
            W(0, 0.0, doubleArrayOf(17.15, 253.9674, 25.0, 1.8261, 0.178856, 0.0)), W(0, 7.3, doubleArrayOf(17.15, 166.2880, 25.0, 6.2696, 0.144074, 0.0)),
            W(0, 19.0, doubleArrayOf(17.15, 25.7609, 25.0, 13.3913, 0.028986, 0.0)), W(2, 0.0, doubleArrayOf(75.46, 117.3438, 26.0, 8.75, 0.124658, 0.0)),
            W(2, 7.3, doubleArrayOf(75.46, 267.5547, 26.0, 1.1375, 0.121875, 0.977296)), W(2, 19.0, doubleArrayOf(75.46, 65.5469, 26.0, 11.375, 0.104110, 0.990054)),
            W(6, 0.0, doubleArrayOf(192.08, 265.9783, 21.0, 1.2174, 0.130435, 0.0)), W(6, 7.3, doubleArrayOf(192.08, 178.2989, 21.0, 5.6609, 0.148839, 0.0)),
            W(6, 19.0, doubleArrayOf(192.08, 37.7717, 21.0, 12.7826, 0.057971, 0.0)),
        )
        for (x in want) {
            val p = HeroRunes.pose(x.i, x.t, 343f, 260f)
            assertEquals(HeroRunes.SPECS[x.i].glyph, p.glyph)
            assertEquals(HeroRunes.SPECS[x.i].silver, p.silver)
            doubleArrayOf(p.x, p.y, p.size, p.rotationDegrees, p.opacity, HeroRunes.flare(x.i, x.t))
                .forEachIndexed { k, v -> assertEquals("hero rune ${x.i} at ${x.t}", x.v[k], v, 1e-3) }
        }
        val still = listOf(17.15 to 195.0, 46.305 to 174.2, 75.46 to 109.2, 104.615 to 88.4, 133.77 to 67.6, 162.925 to 236.6, 192.08 to 215.8)
        still.forEachIndexed { i, (x, y) ->
            val p = HeroRunes.still(i, 343f, 260f)
            assertEquals("still $i x", x, p.x, 1e-3)
            assertEquals("still $i y", y, p.y, 1e-3)
            assertEquals(0.0, p.rotationDegrees, 0.0)
            assertEquals(0.10, p.opacity, 0.0)
        }
        val inner = ThemeArt.METRICS.getValue("heroFrameInset") + 1
        for (width in listOf(328.0, 343.0, 402.0)) HeroRunes.SPECS.forEachIndexed { i, s ->
            val x = width * s.x
            assertTrue("row $i's ink inside the interior at $width", x - 0.30 * s.size >= inner && x + 0.30 * s.size <= width - inner)
            assertTrue("row $i's em box inside the hero at $width", x - s.size / 2 >= 0.0)
        }
        // The lanes with the measured ink (motion.py 1d, on 328, 343 and 402): row j's glow on row i's ink is
        // HERO_GLOW x min(peak, Phi(-(r - ink s_j) / (blur s_j))), r the lane distance less row i's ink.
        fun phi(x: Double) = ArtGeometry.erfc(-x / Math.sqrt(2.0)) / 2
        val ink = GlyphGlowGoldens.INK_HERO
        var laneGlow = 0.0
        for (width in listOf(328.0, 343.0, 402.0)) HeroRunes.SPECS.forEachIndexed { i, s ->
            var glow = 0.0
            HeroRunes.SPECS.forEachIndexed { j, o ->
                if (j != i) {
                    val gap = Math.abs(s.x - o.x) * width
                    assertTrue("rows $i and $j's measured inks never meet at $width", gap >= ink * (s.size + o.size))
                    val r = kotlin.math.max(0.0, gap - ink * s.size)
                    glow += RuneGlow.HERO_GLOW * kotlin.math.min(GlyphGlowGoldens.HERO, phi(-(r - ink * o.size) / (RuneGlow.BLUR * o.size)))
                }
            }
            laneGlow = kotlin.math.max(laneGlow, glow)
        }
        assertEquals("motion.py 1d's lane bound (its inks rounded to 4 places)", GlyphGlowGoldens.HERO_LANE_GLOW, laneGlow, 1e-5)
        // Why the rule changed (the negative of the old one): with the measured ink, "the glow stays off another lane's
        // ink" (gap >= HERO_REACH s_j + ink s_i) is false on the narrowest hero; D-243's 0.30 receiver hid it.
        val crossing = HeroRunes.SPECS.indices.flatMap { i -> HeroRunes.SPECS.indices.filter { it != i }.map { i to it } }
            .filter { (i, j) -> Math.abs(HeroRunes.SPECS[i].x - HeroRunes.SPECS[j].x) * 328 < RuneGlow.HERO_REACH * HeroRunes.SPECS[j].size + ink * HeroRunes.SPECS[i].size }
        assertTrue("a glow reaches a neighbour's measured ink", crossing.isNotEmpty())
    }

    /** D-243 (plan test 4): the hero runes keep left of the ᚠ's glow. The ᚠ measured in the bundled Vardr Runes Carved
     *  (java.awt.Font on the res/font file, the D-215 way) at 118: advance 0.460 em. D-246 (amended on purpose; D-243's
     *  halo started 0.5 x 118 left of the ᚠ's centre and a rune reached 0.71 + 0.8 x its size): the ᚠ's glow is seen to
     *  WatermarkGlow.GLOW_LEFT x 118 (71.41) left of W - 13 - w / 2, and on the narrowest hero (328) every rune's reach
     *  (centre + the farther of 0.71 x size for a turned em box and its glow's RuneGlow.HERO_REACH x size) stays left of
     *  it (motion.py 1d: 201.67 < 216.45; iOS pins 343: 210.07 < 231.45). */
    @Test
    fun the_hero_runes_stay_left_of_the_fehu() {
        val size = ThemeArt.METRICS.getValue("heroWatermark")
        assertEquals(118.0, size, 0.0)
        assertEquals("ᚠ", Runes.BALANCE)
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File("src/main/res/font/vardrrunes_carvedregular.ttf"))
            .deriveFont(size.toFloat())
        val frc = java.awt.font.FontRenderContext(null, false, true)
        val advance = font.createGlyphVector(frc, Runes.BALANCE).getGlyphMetrics(0).advanceX.toDouble()
        assertEquals("motion.py: 0.460 em at 118", 54.28, advance, 0.01)
        val width = 328.0
        assertEquals(0.6052, WatermarkGlow.GLOW_LEFT, 0.0)
        val fehuGlowLeft = width - ThemeArt.METRICS.getValue("heroWatermarkTrailing") - advance / 2 - WatermarkGlow.GLOW_LEFT * size
        val reach = HeroRunes.SPECS.maxOf { width * it.x + kotlin.math.max(0.71, RuneGlow.HERO_REACH) * it.size }
        assertTrue("the hero runes keep left of the ᚠ's glow: $reach < $fehuGlowLeft", reach < fehuGlowLeft)
        assertEquals("motion.py 1d, 328 wide", 201.67, reach, 0.01)
        assertEquals("motion.py 1d, 328 wide", 216.45, fehuGlowLeft, 0.01)
    }

    /** D-243 (plan test 5): the ᚠ glows: 0.16 up to 0.208 on a 9 s cycle; still 0.16, no glow. D-246 (amended on
     *  purpose; D-243's was a disc halo of 0.10, radius 0.5 x 118): over its own blurred shape at 0.305 at the top of the
     *  glow (motion.py 5a: the disc's light over its ink; 1c''s cap 0.625). The twin of iOS's testTheWatermarkGlows. */
    @Test
    fun the_watermark_glows() {
        assertEquals(listOf(0.16, 0.208, 9.0, 0.55, 0.70, 0.88, 0.305, 0.6052), listOf(WatermarkGlow.REST, WatermarkGlow.PEAK,
            WatermarkGlow.CYCLE, WatermarkGlow.RISE, WatermarkGlow.TOP, WatermarkGlow.FALL, WatermarkGlow.GLYPH_GLOW, WatermarkGlow.GLOW_LEFT))
        val want = listOf( // t, glow, alpha, glyph glow
            doubleArrayOf(0.0, 0.0, 0.16, 0.0), doubleArrayOf(4.95, 0.0, 0.16, 0.0), doubleArrayOf(5.6, 0.468099, 0.182469, 0.142770),
            doubleArrayOf(6.3, 1.0, 0.208, 0.305), doubleArrayOf(7.0, 0.615709, 0.189554, 0.187791), doubleArrayOf(7.92, 0.0, 0.16, 0.0),
            doubleArrayOf(8.99, 0.0, 0.16, 0.0),
        )
        for (w in want) {
            assertEquals("glow at ${w[0]}", w[1], WatermarkGlow.glow(w[0]), 1e-6)
            assertEquals("alpha at ${w[0]}", w[2], WatermarkGlow.alpha(w[0], still = false), 1e-6)
            assertEquals("glyph glow at ${w[0]}", w[3], WatermarkGlow.glyphGlowAlpha(w[0], still = false), 1e-6)
            assertEquals("still at ${w[0]}", 0.16, WatermarkGlow.alpha(w[0], still = true), 0.0)
            assertEquals("no glow when still at ${w[0]}", 0.0, WatermarkGlow.glyphGlowAlpha(w[0], still = true), 0.0)
        }
        for (step in 0 until 900) {
            val t = step * 0.02
            assertEquals("a 9 s cycle at $t", WatermarkGlow.glow(t), WatermarkGlow.glow(t + 9), 1e-9)
            assertTrue(WatermarkGlow.alpha(t, false) in (0.16 - 1e-12)..(0.208 + 1e-12))
        }
    }

    /** D-243 (plan test 6, the engine half): You's ring turns clockwise, one turn per 90 s from upright, in [0, 360);
     *  upright when still or when the clock is not finite. The twin of iOS's testTheRingTurns. */
    @Test
    fun the_ring_turns() {
        // D-246 (amended on purpose; D-243's lit halo was 0.35, radius 0.5): the lit runes glow from their glyphs at 0.995.
        assertEquals(listOf(90.0, 0.0, 0.995), listOf(RingTurn.PERIOD, RingTurn.STILL, RingTurn.LIT_GLOW))
        val want = listOf(0.0 to 0.0, 22.5 to 90.0, 45.0 to 180.0, 89.99 to 359.96, 90.0 to 0.0, 900.0 to 0.0, -22.5 to 270.0,
            Double.NaN to 0.0, Double.POSITIVE_INFINITY to 0.0, Double.NEGATIVE_INFINITY to 0.0)
        for ((s, d) in want) assertEquals("ring at $s", d, RingTurn.degrees(s), 1e-6)
        assertEquals(0.0, RingTurn.degrees(RingTurn.STILL), 0.0)
        for (step in 0 until 2000) {
            val d = RingTurn.degrees(step * 0.37 - 100)
            assertTrue("in [0, 360) at step $step", d >= 0.0 && d < 360.0)
        }
    }

    /** D-243 (plan test 6, the renderers' half): You's ring turns ([artTurn] on the art's clock, the layer alone); the
     *  picture over it does not; the done check's ring keeps its moment's own turn (negative: no artTurn there). Every lit
     *  rune carries the static glow (D-246: its own glyph's, on its own turned layer; D-243's halo) in RuneRing itself, so
     *  both rings have it. The twin of iOS's testYouRingTurnsAndTheDoneRingKeepsItsOwnClock. */
    @Test
    fun you_ring_turns_and_the_done_ring_keeps_its_own_clock() {
        val hub = src("AccountHubScreen.kt")
        assertEquals("You's ring turns", 1, hub.split("RuneRing(done, total, dark, Modifier.artTurn { RingTurn.degrees(it) })").size - 1)
        assertEquals("one turning thing on You", 1, hub.split("artTurn").size - 1)
        val box = hub.substringAfter("Box(Modifier.size(ThemeArt.m(\"ringBox\").dp), contentAlignment = Alignment.Center) {")
        assertTrue("the picture after the ring, outside its turn",
            box.indexOf("AvatarView(avatar, name, 104.dp, dark)") > box.indexOf("Modifier.artTurn { RingTurn.degrees(it) })"))
        val done = src("DoneMoments.kt")
        assertFalse("the done ring keeps its moment's turn", "artTurn" in done)
        assertTrue("its own clock", "Modifier.graphicsLayer { rotationZ = CheckMotion.pose(p.elapsed(CheckMotion.STILL_ELAPSED)).degrees.toFloat() }," in done)
        assertTrue("RuneRing(1, 1, dark)" in done)
        val ring = src("YouProgress.kt").substringAfter("fun RuneRing(done: Int, total: Int, dark: Boolean, modifier: Modifier = Modifier) {").substringBefore("\n}\n")
        assertTrue("the turn reaches the ring's box", "Box(modifier.size(ThemeArt.m(\"ringBox\").dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {" in ring)
        // D-246 (amended on purpose; D-243's was one remembered unit radial brush drawn behind the runes): each lit rune's
        // glow is its own glyph's cached mask (built in composition, once), in the ring's colour at LIT_GLOW, drawn behind
        // the rune on its own turned layer with the glow's glyph on the rune's baseline.
        assertTrue("the lit runes' masks, built once", "val glows = remember(raster, lit) {\n        List(lit.coerceAtMost(Runes.FUTHARK.size)) { GlyphGlow.mask(Runes.FUTHARK[it], ThemeArt.m(\"runeRingSize\").toDouble(), RuneGlow.BLUR, raster) }" in ring)
        assertTrue("one remembered tint", "val tint = remember(ring) { ColorFilter.tint(ring) }" in ring)
        assertTrue("each rune's baseline from its layout", "onTextLayout = { baselines[i] = it.firstBaseline }," in ring)
        assertTrue("on the rune's turned layer, behind it, lit runes only", ".rotate(angle)\n                    .then(\n                        if (i < glows.size) {\n                            Modifier.drawBehind { with(ArtDraw) { glyphGlow(glows[i], 0f, baselines[i], RingTurn.LIT_GLOW.toFloat(), tint) } }" in ring)
    }

    /** D-246 (decision 195, the founder: "make the actual rune glow"; plan: "no disc drawing left"): no rune glow draws
     *  a circle, an oval or a radial gradient: not the rune layers' caches and draws (page, hero, the shared glowingRune
     *  and glyphGlow), not GlyphGlow, not the page's rune canvas, not the Norse hero, not the ring's lit runes. The masks
     *  are built only in composition (GlyphGlow.mask inside a remember), never by a draw; GlyphGlow fills the glyph's
     *  own outline and blurs it with its own Gaussian, never a blur filter. The negative: the detector catches D-243's
     *  halo, word for word. The twin of iOS's testNoRuneGlowDrawsADisc. */
    @Test
    fun no_rune_glow_draws_a_disc() {
        fun code(text: String) = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("(?<![:\"])//.*"), "")
        fun drawsADisc(text: String) = listOf("drawCircle(", "drawOval(", "addOval(", "drawArc(", "radialGradient", "RadialGradient",
            "RadialGradientShader", "CircleShape", "Oval(").any { it in code(text) }
        assertTrue("the detector sees D-243's halo", drawsADisc("""
            }) { drawCircle(if (p.silver) glow.silver else glow.gold, radius = 1f, center = Offset.Zero, alpha = halo.toFloat()) }
        """))
        assertTrue("and its brush", drawsADisc("internal val gold: Brush = Brush.radialGradient(listOf(RUNE_GOLD, RUNE_GOLD.copy(alpha = 0f)), center = Offset.Zero, radius = 1f)"))
        assertFalse("but not a comment", drawsADisc("// D-243's drawCircle halo is gone"))
        assertFalse(drawsADisc("if (glow > 0.0) glyphGlow(mask, left, top + glyph.firstBaseline, glow.toFloat(), if (p.silver) cache.silver else cache.gold)"))
        val motion = src("ThemeMotion.kt")
        val runeParts = motion.substringAfter("    /** D-243: a rune layer's drawing, laid out once").substringBefore("    /** D-221 (L45, motion.py part 5): the settled burst")
        assertTrue("the slice holds the rune draws", "fun DrawScope.glowingRune(" in runeParts && "fun DrawScope.glyphGlow(" in runeParts && "class NorseHeroCache(" in runeParts)
        assertFalse("no disc in the runes' caches or draws", drawsADisc(runeParts))
        val glyphGlow = motion.substringAfter("object GlyphGlow {").substringBefore("\n}\n")
        assertFalse("GlyphGlow draws the glyph, never a disc", drawsADisc(glyphGlow))
        assertTrue("it fills the glyph's own outline", "p.getTextPath(glyph, 0, glyph.length, x.toFloat(), baseline.toFloat(), outline)" in glyphGlow)
        assertTrue("on a software bitmap", "android.graphics.Canvas(bitmap).drawPath(outline, p)" in glyphGlow)
        for (filter in listOf("BlurMaskFilter", "RenderEffect", "BlurEffect", "setShadowLayer", "Shadow(")) {
            assertFalse("GlyphGlow blurs with its own Gaussian, not $filter", filter in code(glyphGlow))
        }
        val canvas = motion.substringAfter("private fun Modifier.runesGround(").substringBefore("\n}\n").substringAfter("}.artCanvas(0.0, motion) { t ->")
        assertTrue("the page's rune canvas is found", "runes(t, still, strength, glows)" in canvas)
        assertFalse(drawsADisc(canvas))
        val norse = motion.substringAfter("        AppTheme.NORSE -> {").substringBefore("        AppTheme.VARDR -> {")
        assertFalse("the Norse hero's runes and ᚠ", drawsADisc(norse))
        val ring = src("YouProgress.kt").substringAfter("fun RuneRing(done: Int, total: Int, dark: Boolean, modifier: Modifier = Modifier) {").substringBefore("\n}\n")
        assertFalse("the ring's lit runes glow from their glyphs", drawsADisc(ring))
        val main = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles()!!.filter { it.name.endsWith(".kt") }.joinToString("\n") { it.readText() }
        for (gone in listOf("RuneGlow.HALO", "HALO_RADIUS", "LIT_HALO", "haloAlpha", "fehuHalo")) assertFalse("the disc's $gone is gone", gone in main)
        // Built once: GlyphGlow.mask is asked only inside a remember (the layer caches, the hero's cache, the ring), the
        // draws take the masks they hold.
        assertEquals("the layers' masks", 1, motion.split("private fun glowOf(p: RunePose, raster: GlyphGlow.Raster): GlyphGlow.Mask = GlyphGlow.mask(p.glyph, p.size, RuneGlow.BLUR, raster)").size - 1)
        assertEquals("asked in two places in the art", 2, motion.split("GlyphGlow.mask(").size - 1)
        assertTrue("the hero's, in its remembered cache", norse.indexOf("val fehuGlow = GlyphGlow.mask(") in norse.indexOf("val cache = remember(") until norse.indexOf("fun DrawScope.fill()"))
        assertEquals("the ring's, in its remember", 1, ring.split("GlyphGlow.mask(").size - 1)
    }

    /** D-246: GlyphGlow builds each mask once a process: a second ask, or a size in the same whole-dp bucket, builds
     *  nothing (the counter, and the same mask); a new size or blur builds one (the negative). The mask is the glyph's box
     *  plus 4 sigma a side at six pixels a sigma, and its blurred peak is motion.py 5a's for every Futhark glyph (the same
     *  Gaussian of the same outline: the JVM fills it through java.awt, the phone through Paint.getTextPath), so the
     *  floors' model (the peak under the full core) is the shell's. The twin of iOS's
     *  testGlyphGlowIsBuiltOnceAndIsMotionPysMask. */
    @Test
    fun glyph_glow_is_built_once_and_is_motion_pys_mask() {
        val before = GlyphGlow.builds
        val a = GlyphGlow.mask("\u16DE", 41.0, RuneGlow.BLUR, AwtGlyphRaster)
        assertEquals("the first ask builds it", before + 1, GlyphGlow.builds)
        val b = GlyphGlow.mask("\u16DE", 41.0, RuneGlow.BLUR, AwtGlyphRaster)
        val c = GlyphGlow.mask("\u16DE", 40.7, RuneGlow.BLUR, AwtGlyphRaster)
        repeat(50) { GlyphGlow.mask("\u16DE", 41.0, RuneGlow.BLUR, AwtGlyphRaster) }
        assertEquals("never again, in the same bucket too", before + 1, GlyphGlow.builds)
        assertTrue("the cached mask itself", a === b && a === c)
        GlyphGlow.mask("\u16DE", 42.0, RuneGlow.BLUR, AwtGlyphRaster)
        assertEquals("a new size builds one", before + 2, GlyphGlow.builds)
        GlyphGlow.mask("\u16DE", 42.0, 0.12, AwtGlyphRaster)
        assertEquals("a new blur builds one", before + 3, GlyphGlow.builds)
        // The box: ascent to descent (1.15 em, as iOS's Text box) and the advance, plus 4 sigma a side, to a whole pixel.
        val sigma = 0.18 * 41
        assertEquals("six pixels a sigma", kotlin.math.min(3.0, 6 / sigma), a.scale, 1e-12)
        assertTrue("the height", a.height / a.scale >= 1.15 * 41 + 8 * sigma - 0.5 && a.height / a.scale < 1.15 * 41 + 8 * sigma + 0.5 + 1 / a.scale)
        assertTrue("the width: U+16DE's advance is 0.62 em", a.width / a.scale >= 0.62 * 41 + 8 * sigma - 0.5)
        assertEquals("the glyph's origin centres its box across", (a.width - 0.62 * 41 * a.scale) / 2, a.originX, 0.5 * a.scale)
        assertEquals("the tests' raster keeps the alpha", a.width * a.height, a.alpha!!.size)
        // D-246 fix wave (M2): a raster without the test seam (the phone's) keeps no alpha, only the image and the peak.
        val phoneLike = object : GlyphGlow.Raster by AwtGlyphRaster {
            override val keepsAlpha: Boolean get() = false
        }
        val kept = GlyphGlow.mask("\u16DE", 44.0, RuneGlow.BLUR, phoneLike)
        assertEquals("no doubles kept off the seam", null, kept.alpha)
        assertTrue("the peak is kept", kept.peak > 0.3)
        // motion.py 5a's peaks (blur 0.18), each glyph at a page rune's size: motion.py's mask (within 0.01).
        for ((glyph, peak) in GlyphGlowGoldens.PEAKS) {
            assertEquals("U+${glyph.codePointAt(0).toString(16).uppercase()}'s peak", peak, GlyphGlow.mask(glyph, 20.0, RuneGlow.BLUR, AwtGlyphRaster).peak, 0.01)
        }
        // D-246 fix wave (M1): the floors (AppearanceTest) take motion.py's layer peak, so the link to the masks the shell
        // DRAWS is one-sided and tight: every mask a layer draws, at the size it draws it, peaks at most motion.py's layer
        // peak + 0.002 (the 8-bit image rounds within 0.002 of it). A two-sided 0.01 let a page peak of 0.353 through.
        var pagePeak = 0.0
        for (i in 0 until RuneDrift.COUNT) {
            val p = RuneDrift.still(i, 0f, 0f)
            val peak = GlyphGlow.mask(p.glyph, p.size, RuneGlow.BLUR, AwtGlyphRaster).peak
            assertTrue("page rune $i at ${p.size}: $peak", peak <= GlyphGlowGoldens.PAGE + 0.002)
            pagePeak = kotlin.math.max(pagePeak, peak)
        }
        var heroPeak = 0.0
        HeroRunes.SPECS.forEachIndexed { i, s ->
            val peak = GlyphGlow.mask(s.glyph, s.size, RuneGlow.BLUR, AwtGlyphRaster).peak
            assertTrue("hero row $i at ${s.size}: $peak", peak <= GlyphGlowGoldens.HERO + 0.002)
            heroPeak = kotlin.math.max(heroPeak, peak)
        }
        val fehuPeak = GlyphGlow.mask(Runes.BALANCE, ThemeArt.METRICS.getValue("heroWatermark"), RuneGlow.BLUR, AwtGlyphRaster).peak
        assertTrue("the ᚠ at 118: $fehuPeak", fehuPeak <= GlyphGlowGoldens.FEHU + 0.002)
        assertTrue("the page's masks are motion.py's", pagePeak > GlyphGlowGoldens.PAGE - 0.01)
        assertTrue("the hero's masks are motion.py's", heroPeak > GlyphGlowGoldens.HERO - 0.01)
        assertTrue("the ᚠ's mask is motion.py's", fehuPeak > GlyphGlowGoldens.FEHU - 0.01)
        // The blur keeps the light (a Gaussian sums to 1) and is motion.py's: one lit pixel spreads to the kernel.
        val one = IntArray(41 * 41).also { it[20 * 41 + 20] = 255 }
        val spread = GlyphGlow.blur(one, 41, 41, 3.0)
        assertEquals("the light is kept", 1.0, spread.sum(), 1e-9)
        assertEquals("the centre: the kernel's middle squared", 1 / (2 * Math.PI * 9.0), spread[20 * 41 + 20], 2e-4)
        assertEquals("cut at 4 sigma", 0.0, spread[20 * 41 + 20 + 13], 0.0)
    }

    /** D-246 fix wave (M3): the glow sits on the glyph's ink, measured (the twin of iOS's testTheGlowSitsOnTheGlyphsInk).
     *  The mask's alpha centre of mass (a Gaussian keeps it) lands within 0.5 px of the glyph's own centre of mass: its
     *  outline's area by Green's theorem over the flattened java.awt.geom.Area (no raster), the glyph's origin put on the
     *  mask's ([GlyphGlow.Mask.originX], [GlyphGlow.Mask.baseline]), for the ᚠ at 118, ᛞ at 27 and ᛃ at 25. The negative: a
     *  raster that fills the glyph 0.6 dp low (iOS's stage 1 found text drawing 0.54 pt off) is caught. */
    @Test
    fun the_glow_sits_on_the_glyphs_ink() {
        val face = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File("src/main/res/font/vardrrunes_carvedregular.ttf"))
        val frc = java.awt.font.FontRenderContext(null, true, true)
        fun glowCentre(m: GlyphGlow.Mask): Pair<Double, Double> {
            val a = m.alpha!!
            var sx = 0.0
            var sy = 0.0
            var total = 0.0
            for (y in 0 until m.height) for (x in 0 until m.width) {
                val v = a[y * m.width + x]
                sx += v * (x + 0.5)
                sy += v * (y + 0.5)
                total += v
            }
            return sx / total to sy / total
        }
        fun inkCentre(glyph: String, m: GlyphGlow.Mask, size: Double): Pair<Double, Double> {
            val outline = java.awt.geom.Area(face.deriveFont((size * m.scale).toFloat()).createGlyphVector(frc, glyph)
                .getOutline(m.originX.toFloat(), m.baseline.toFloat()))
            val it = outline.getPathIterator(null, 0.001)
            val c = DoubleArray(6)
            var area = 0.0
            var cx = 0.0
            var cy = 0.0
            var (x0, y0) = 0.0 to 0.0
            var (px, py) = 0.0 to 0.0
            fun edge(x1: Double, y1: Double) {
                val cross = px * y1 - x1 * py
                area += cross
                cx += (px + x1) * cross
                cy += (py + y1) * cross
                px = x1
                py = y1
            }
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    java.awt.geom.PathIterator.SEG_MOVETO -> { x0 = c[0]; y0 = c[1]; px = x0; py = y0 }
                    java.awt.geom.PathIterator.SEG_LINETO -> edge(c[0], c[1])
                    java.awt.geom.PathIterator.SEG_CLOSE -> edge(x0, y0)
                }
                it.next()
            }
            return cx / (3 * area) to cy / (3 * area)
        }
        for ((glyph, size) in listOf(Runes.BALANCE to 118.0, "\u16DE" to 27.0, "\u16C3" to 25.0)) {
            val m = GlyphGlow.mask(glyph, size, RuneGlow.BLUR, AwtGlyphRaster)
            val glow = glowCentre(m)
            val ink = inkCentre(glyph, m, size)
            assertEquals("$glyph at $size: across", ink.first, glow.first, 0.5)
            assertEquals("$glyph at $size: down", ink.second, glow.second, 0.5)
        }
        // The negative: the glyph filled 0.6 dp low (a key of its own, so the real mask stays cached).
        val low = object : GlyphGlow.Raster by AwtGlyphRaster {
            override fun coverage(glyph: String, em: Double, width: Int, height: Int, x: Double, baseline: Double): IntArray =
                AwtGlyphRaster.coverage(glyph, em, width, height, x, baseline + 0.6 * em / 27.0)
            override val keepsAlpha: Boolean get() = true
        }
        val off = GlyphGlow.mask("\u16DE", 27.0, RuneGlow.BLUR + 1e-9, low)
        assertTrue("a glow 0.6 dp low is caught", kotlin.math.abs(glowCentre(off).second - inkCentre("\u16DE", off, 27.0).second) > 0.5)
    }

    /** D-246: the glow lies under its glyph on the glyph's own turned layer, its glyph's origin on the crisp glyph's: the
     *  page's and the hero's runes in one transform (the glow first, then the text), the ᚠ at its text's origin and
     *  baseline, and glyphGlow scales the mask's pixels to the density about that origin. The twin of iOS's "the glow
     *  turns with its glyph and lies under it". */
    @Test
    fun the_glow_lies_under_its_glyph_on_its_turned_layer() {
        val motion = src("ThemeMotion.kt")
        val glowing = motion.substringAfter("private fun DrawScope.glowingRune(").substringBefore("\n    }\n")
        assertTrue(glowing.contains("""withTransform({
            translate(p.x.toFloat(), p.y.toFloat())
            rotate(p.rotationDegrees.toFloat(), Offset.Zero)
        }) {
            val left = -glyph.size.width / 2f
            val top = -glyph.size.height / 2f
            if (glow > 0.0) glyphGlow(mask, left, top + glyph.firstBaseline, glow.toFloat(), if (p.silver) cache.silver else cache.gold)
            drawText(glyph, color = if (p.silver) RUNE_SILVER else RUNE_GOLD, topLeft = Offset(left, top), alpha = core.toFloat())
        }"""))
        assertEquals("one transform: the glow turns with its glyph", 1, glowing.split("withTransform(").size - 1)
        assertEquals("one glow, the one inside it", 1, glowing.split("glyphGlow(").size - 1)
        val draw = motion.substringAfter("fun DrawScope.glyphGlow(mask: GlyphGlow.Mask, x: Float, baseline: Float, alpha: Float, tint: ColorFilter) {").substringBefore("\n    }\n")
        assertTrue("the mask's pixels at this density", "val f = density / mask.scale.toFloat()" in draw)
        assertTrue("its glyph's origin on the given one", "translate(x - mask.originX.toFloat() * f, baseline - mask.baseline.toFloat() * f)" in draw)
        assertTrue("scaled about it", "scale(f, f, Offset.Zero)" in draw)
        assertTrue("a cached image, tinted", "drawImage(image, alpha = alpha, colorFilter = tint)" in draw)
        val norse = motion.substringAfter("        AppTheme.NORSE -> {").substringBefore("        AppTheme.VARDR -> {")
        val glow = "if (glow > 0f) with(ArtDraw) { glyphGlow(cache.fehuGlow, left, top + glyph.firstBaseline, glow, cache.fehuTint) }"
        assertTrue("the ᚠ's glow on its text's origin", glow in norse)
        assertTrue("under the ᚠ", norse.indexOf(glow) < norse.indexOf("drawText(glyph, color = accent, alpha = (WatermarkGlow.alpha(t, still) * k).toFloat(), topLeft = Offset(left, top))"))
        assertTrue("at its alpha", "val glow = (WatermarkGlow.glyphGlowAlpha(t, still) * k).toFloat()" in norse)
        assertTrue("the hero's runes at the hero's glow", "RuneGlow.glow(core, RuneGlow.HERO_FLARE, RuneGlow.HERO_GLOW, still)" in motion)
        assertTrue("the page's at the page's", "RuneGlow.glow(core, RuneGlow.PAGE_FLARE, RuneGlow.PAGE_GLOW, still)" in motion)
    }

    /** D-243 (plan test 7): parity. Every `RuneGlow`, `WatermarkGlow` and `RingTurn` constant in ThemeMotion.swift is
     *  Kotlin's, and none is on one shell only; the hero runes' table equals iOS's `HeroRunes.specs`, row by row. */
    @Test
    fun the_livelier_art_matches_ios() {
        assertEquals(
            mapOf(
                // D-246 (amended on purpose; D-243's halo and haloRadius): the glyph glow's blur, alphas and reaches.
                "pageFlare" to listOf(RuneGlow.PAGE_FLARE), "heroFlare" to listOf(RuneGlow.HERO_FLARE), "blur" to listOf(RuneGlow.BLUR),
                "pageGlow" to listOf(RuneGlow.PAGE_GLOW), "heroGlow" to listOf(RuneGlow.HERO_GLOW),
                "pageReach" to listOf(RuneGlow.PAGE_REACH), "heroReach" to listOf(RuneGlow.HERO_REACH),
                "rise" to listOf(RuneGlow.RISE), "top" to listOf(RuneGlow.TOP),
                "fall" to listOf(RuneGlow.FALL), "ease" to RuneGlow.EASE,
            ),
            swiftConstants("RuneGlow"),
        )
        assertEquals(
            mapOf(
                "rest" to listOf(WatermarkGlow.REST), "peak" to listOf(WatermarkGlow.PEAK), "cycle" to listOf(WatermarkGlow.CYCLE),
                "rise" to listOf(WatermarkGlow.RISE), "top" to listOf(WatermarkGlow.TOP), "fall" to listOf(WatermarkGlow.FALL),
                "glyphGlow" to listOf(WatermarkGlow.GLYPH_GLOW), "glowLeft" to listOf(WatermarkGlow.GLOW_LEFT),
            ),
            swiftConstants("WatermarkGlow"),
        )
        assertEquals(
            mapOf(
                "period" to listOf(RingTurn.PERIOD), "still" to listOf(RingTurn.STILL), "litGlow" to listOf(RingTurn.LIT_GLOW),
            ),
            swiftConstants("RingTurn"),
        )
        // D-246: GlyphGlow's one constant, six pixels a sigma (the blur's own formula is twinned in the build test).
        assertEquals(mapOf("pixelsPerSigma" to listOf(GlyphGlow.PIXELS_PER_SIGMA)), swiftConstants("GlyphGlow"))
        val block = after("enum HeroRunes {").substringAfter("static let specs: [Spec] = [").substringBefore("\n    ]")
        val rows = Regex("""Spec\(glyph: "((?:\\u\{[0-9A-F]+\})+)", silver: (true|false), x: ([0-9.]+), size: ([0-9.]+), duration: ([0-9.]+), lead: ([0-9.]+), cycle: ([0-9.]+), flareLead: ([0-9.]+), stillY: ([0-9.]+)\)""")
            .findAll(block).toList()
        assertEquals(7, rows.size)
        assertEquals(
            HeroRunes.SPECS,
            rows.map { m ->
                val v = m.groupValues.drop(3).map { it.toDouble() }
                HeroRuneSpec(unescape(m.groupValues[1]), m.groupValues[2] == "true", v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            },
        )
    }

    /** The constants of `enum <name>` in ThemeMotion.swift: every `static let x = <number or tuple of numbers>`. A missing
     *  enum is an assertion, not a crash. */
    private fun swiftConstants(name: String): Map<String, List<Double>> {
        val open = Regex("""(?m)^enum $name \{$""").find(swift)
        assertTrue("ThemeMotion.swift has enum $name", open != null)
        val start = open!!.range.first
        val end = swift.indexOf("\n}\n", start)
        assertTrue("enum $name closes", end > start)
        return Regex("""(?m)^\s*static let (\w+)(?:\s*:[^=\n]+)?\s*=\s*([^\n]+?)\s*(?://.*)?$""").findAll(swift.substring(start, end))
            .filter { Regex("""^\(?\s*-?[0-9.]+(\s*,\s*-?[0-9.]+)*\s*\)?$""").matches(it.groupValues[2]) }
            .associate { it.groupValues[1] to numbers(it.groupValues[2]) }
    }

    /** D-221 parity: every `SettledBurst` and `CelebrationTiming` constant in ThemeMotion.swift is Kotlin's, and no
     *  constant is on one shell only. */
    @Test
    fun the_burst_matches_ios() {
        val burst = mapOf(
            "count" to listOf(SettledBurst.COUNT.toDouble()), "originX" to listOf(SettledBurst.ORIGIN_X),
            "originY" to listOf(SettledBurst.ORIGIN_Y), "lbScreen" to listOf(SettledBurst.LB_SCREEN),
            "baseDistance" to listOf(SettledBurst.BASE_DISTANCE), "distanceStep" to listOf(SettledBurst.DISTANCE_STEP),
            "outward" to listOf(SettledBurst.OUTWARD), "drop" to listOf(SettledBurst.DROP),
            "duration" to listOf(SettledBurst.DURATION), "durationStep" to listOf(SettledBurst.DURATION_STEP),
            "peakAt" to listOf(SettledBurst.PEAK_AT), "startScale" to listOf(SettledBurst.START_SCALE),
            "endScale" to listOf(SettledBurst.END_SCALE), "angleStep" to listOf(SettledBurst.ANGLE_STEP),
            "ease" to SettledBurst.EASE, "auroraDot" to listOf(SettledBurst.AURORA_DOT),
            "auroraGlow" to listOf(SettledBurst.AURORA_GLOW), "norseRune" to listOf(SettledBurst.NORSE_RUNE),
            "vardrSquare" to listOf(SettledBurst.VARDR_SQUARE),
        )
        assertEquals(burst, swiftConstants("SettledBurst"))
        val timing = mapOf(
            "total" to listOf(CelebrationTiming.TOTAL), "dimTo" to listOf(CelebrationTiming.DIM_TO),
            "dimIn" to listOf(CelebrationTiming.DIM_IN), "messageIn" to listOf(CelebrationTiming.MESSAGE_IN),
            "messageRise" to listOf(CelebrationTiming.MESSAGE_RISE), "messageTop" to listOf(CelebrationTiming.MESSAGE_TOP),
        )
        assertEquals(timing, swiftConstants("CelebrationTiming"))
    }

    /** D-224 (motion.py part 6, retyped from motion.out): the CSS cubic-bezier at nine points, the check's pose at 14
     *  times, the toast's at ten times moving and still; the twin of iOS's testTheDoneMotionMatchesTheGoldens. */
    @Test
    fun the_done_motion_matches_the_goldens() {
        val circle = listOf(0.6, 0.0, 0.2, 1.0)
        val tick = listOf(0.3, 0.0, 0.2, 1.0)
        val pop = listOf(0.3, 1.6, 0.5, 1.0)
        val ease = listOf(
            Triple(circle, 0.25, 0.095833), Triple(circle, 0.5, 0.704685), Triple(circle, 0.75, 0.956227),
            Triple(tick, 0.25, 0.348935), Triple(tick, 0.5, 0.797153), Triple(tick, 0.75, 0.960733),
            Triple(pop, 0.25, 0.914187), Triple(pop, 0.5, 1.110044), Triple(pop, 0.75, 1.044118),
        )
        for ((curve, u, y) in ease) assertEquals("$curve at $u", y, Bezier.y(u, curve), 1e-6)
        assertEquals(0.0, Bezier.y(-0.5, pop), 0.0)
        assertEquals(1.0, Bezier.y(1.5, pop), 0.0)
        assertEquals(listOf(circle, tick, pop), listOf(CheckMotion.CIRCLE_EASE, CheckMotion.TICK_EASE, CheckMotion.POP_EASE))
        // SettledBurst's ease now runs through Bezier; its goldens are unchanged.
        for ((u, y) in listOf(0.1 to 0.332602, 0.25 to 0.676225, 0.5 to 0.909972, 0.75 to 0.983712, 0.9 to 0.997765)) {
            assertEquals("burst ease at $u", y, SettledBurst.ease(u), 1e-6)
        }
        val check = listOf(
            0.0 to listOf(0.0, 0.0, 1.0, 0.0),
            0.2 to listOf(0.300932, 0.0, 1.0, 5.1429),
            0.4 to listOf(0.947369, 0.0, 1.0, 10.2857),
            0.5 to listOf(0.996614, 0.108372, 1.0, 12.8571),
            0.55 to listOf(1.0, 0.453045, 1.0, 14.1429),
            0.6 to listOf(1.0, 0.733659, 1.0, 15.4286),
            0.7 to listOf(1.0, 0.979698, 1.0, 18.0),
            0.8 to listOf(1.0, 1.0, 1.087172, 20.5714),
            0.95 to listOf(1.0, 1.0, 1.08, 24.4286),
            1.1 to listOf(1.0, 1.0, 0.992616, 28.2857),
            1.2 to listOf(1.0, 1.0, 1.0, 30.8571),
            3.5 to listOf(1.0, 1.0, 1.0, 90.0),
            13.99 to listOf(1.0, 1.0, 1.0, 359.7429),
            14.0 to listOf(1.0, 1.0, 1.0, 0.0),
        )
        for ((t, want) in check) {
            val p = CheckMotion.pose(t)
            assertEquals("circle at $t", want[0], p.circle, 1e-5)
            assertEquals("tick at $t", want[1], p.tick, 1e-5)
            assertEquals("scale at $t", want[2], p.scale, 1e-5)
            assertEquals("degrees at $t", want[3], p.degrees, 1e-3)
        }
        val toast = listOf(
            -0.1 to null, 0.0 to (0.0 to 10.0), 0.05 to (0.676225 to 3.237752), 0.1 to (0.909972 to 0.900279),
            0.2 to (1.0 to 0.0), 1.5 to (1.0 to 0.0), 2.75 to (1.0 to 0.0), 2.875 to (0.5 to 0.0), 2.99 to (0.04 to 0.0),
            3.0 to null,
        )
        for ((t, want) in toast) {
            val moving = ToastMotion.pose(t, still = false)
            val still = ToastMotion.pose(t, still = true)
            if (want == null) {
                assertEquals("gone at $t (moving)", null, moving)
                assertEquals("gone at $t (still)", null, still)
                continue
            }
            assertTrue("shown at $t", moving != null && still != null)
            assertEquals("opacity at $t", want.first, moving!!.opacity, 1e-5)
            assertEquals("dy at $t", want.second, moving.dy, 1e-5)
            assertEquals("still opacity at $t", 1.0, still!!.opacity, 0.0)
            assertEquals("still dy at $t", 0.0, still.dy, 0.0)
        }
    }

    /** D-224: the still frame (Reduce Motion or Low Power) is the end state: everything drawn, upright, unmoving. */
    @Test
    fun the_still_frame_is_the_end_state() {
        val p = CheckMotion.pose(CheckMotion.STILL_ELAPSED)
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0), listOf(p.circle, p.tick, p.scale, p.degrees))
        assertEquals(14.0, CheckMotion.STILL_ELAPSED, 0.0)
    }

    /** D-224: a moment's clock: the time since it started while ticking, else the still time (a clock that is not
     *  finite falls back to it too). */
    @Test
    fun the_moment_clock() {
        assertEquals(3.0, MomentClock.elapsed(tick = true, now = 5.0, startedAt = 2.0, still = 14.0), 0.0)
        assertEquals(14.0, MomentClock.elapsed(tick = false, now = 5.0, startedAt = 2.0, still = 14.0), 0.0)
        assertEquals(14.0, MomentClock.elapsed(tick = true, now = Double.NaN, startedAt = 2.0, still = 14.0), 0.0)
        assertEquals(14.0, MomentClock.elapsed(tick = true, now = Double.POSITIVE_INFINITY, startedAt = 2.0, still = 14.0), 0.0)
        assertEquals(14.0, MomentClock.elapsed(tick = true, now = 5.0, startedAt = Double.NaN, still = 14.0), 0.0)
    }

    /** D-224 parity: every `CheckMotion`, `ToastMotion`, `HapticGate` and `DoneHapticTiming` constant in
     *  ThemeMotion.swift is Kotlin's, and no constant is on one shell only. */
    @Test
    fun the_done_motion_matches_ios() {
        val check = mapOf(
            "circleDraw" to listOf(CheckMotion.CIRCLE_DRAW), "circleEase" to CheckMotion.CIRCLE_EASE,
            "circleDash" to listOf(CheckMotion.CIRCLE_DASH), "tickAt" to listOf(CheckMotion.TICK_AT),
            "tickDraw" to listOf(CheckMotion.TICK_DRAW), "tickEase" to CheckMotion.TICK_EASE,
            "tickDash" to listOf(CheckMotion.TICK_DASH), "popAt" to listOf(CheckMotion.POP_AT),
            "popDuration" to listOf(CheckMotion.POP_DURATION), "popScale" to listOf(CheckMotion.POP_SCALE),
            "popEase" to CheckMotion.POP_EASE, "runeTurn" to listOf(CheckMotion.RUNE_TURN),
            "stillElapsed" to listOf(CheckMotion.STILL_ELAPSED),
        )
        assertEquals(check, swiftConstants("CheckMotion"))
        val toast = mapOf(
            "fadeIn" to listOf(ToastMotion.FADE_IN), "fadeOut" to listOf(ToastMotion.FADE_OUT),
            "total" to listOf(ToastMotion.TOTAL), "rise" to listOf(ToastMotion.RISE), "ease" to ToastMotion.EASE,
        )
        assertEquals(toast, swiftConstants("ToastMotion"))
        assertEquals(mapOf("window" to listOf(HapticGate.WINDOW)), swiftConstants("HapticGate"))
        assertEquals(emptyMap<String, List<Double>>(), swiftConstants("DoneHapticTiming"))
        // The values themselves, retyped from motion.out part 6.
        assertEquals(listOf(0.55, 302.0, 0.45, 0.35, 60.0, 0.7, 0.5, 1.08, 14.0, 14.0), listOf(CheckMotion.CIRCLE_DRAW,
            CheckMotion.CIRCLE_DASH, CheckMotion.TICK_AT, CheckMotion.TICK_DRAW, CheckMotion.TICK_DASH, CheckMotion.POP_AT,
            CheckMotion.POP_DURATION, CheckMotion.POP_SCALE, CheckMotion.RUNE_TURN, CheckMotion.STILL_ELAPSED))
        assertEquals(listOf(0.2, 0.25, 3.0, 10.0), listOf(ToastMotion.FADE_IN, ToastMotion.FADE_OUT, ToastMotion.TOTAL, ToastMotion.RISE))
        assertEquals(listOf(0.2, 0.7, 0.3, 1.0), ToastMotion.EASE)
        assertEquals(1.0, HapticGate.WINDOW, 0.0)
    }

    /** The text after [anchor] in ThemeMotion.swift; a missing anchor names itself rather than reading as a count. */
    private fun after(anchor: String): String {
        check(anchor in swift) { "ThemeMotion.swift has no `$anchor`" }
        return swift.substringAfter(anchor)
    }

    private val number = Regex("""-?[0-9]+(?:\.[0-9]+)?""")
    private fun numbers(text: String) = number.findAll(text).map { it.value.toDouble() }.toList()
    private fun swiftValue(anchor: String) = numbers(after(anchor).substringBefore("\n")).first()
    private fun unescape(s: String) =
        Regex("""\\u\{([0-9A-F]+)\}""").replace(s) { String(Character.toChars(it.groupValues[1].toInt(16))) }

    /** Parity (twins): every value ThemeMotion.swift holds equals Kotlin's: the strengths, the curtains' specs,
     *  stops, sway keys, streaks and hem, the stars, the signs and their majors, the stills and the turn. */
    @Test
    fun the_strengths_and_signs_match_ios() {
        val block = after("static let metrics: [String: Double] = [").substringBefore("]")
        val ios = Regex(""""(\w+)": (-?[0-9.]+)""").findAll(block).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
        assertEquals(Strength.METRICS, ios)
        val signs = after("static let signs = [").substringBefore("]")
        val iosSigns = Regex(""""((?:[^"\\]|\\u\{[0-9A-F]+\})+)"""").findAll(signs).map { unescape(it.groupValues[1]) }.toList()
        assertEquals(CurrencyField.SIGNS, iosSigns)
    }

    @Test
    fun the_curtains_stars_majors_and_turn_match_ios() {
        val specs = Regex("""Spec\(left: (-?[0-9.]+), height: ([0-9.]+), period: ([0-9.]+), delay: (-?[0-9.]+), lightOpacity: ([0-9.]+), nightOpacity: ([0-9.]+)""")
            .findAll(after("static let specs: [Spec] = [")).map { m -> m.groupValues.drop(1).map { it.toDouble() } }.toList()
        assertEquals(Curtains.SPECS.map { listOf(it.left, it.height, it.period, it.delay, it.lightOpacity, it.nightOpacity) }, specs)
        val stops = Regex("""Stop\(r: ([0-9.]+), g: ([0-9.]+), b: ([0-9.]+), alpha: ([0-9.]+), at: ([0-9.]+)\)""")
            .findAll(after("private static let pinkGreenCyan: [Stop] = [")).map { m -> m.groupValues.drop(1).map { it.toDouble() } }.toList()
        fun flat(list: List<CurtainStop>) = list.map { listOf(it.r.toDouble(), it.g.toDouble(), it.b.toDouble(), it.alpha, it.at) }
        assertEquals("pink-green-cyan, then curtain 3's own", flat(Curtains.SPECS[0].stops) + flat(Curtains.SPECS[2].stops), stops)
        assertEquals(Curtains.SPECS[0].stops, Curtains.SPECS[1].stops)
        assertTrue("iOS curtain 2 shares the stops", "stops: pinkGreenCyan),\n        Spec(left: 0.30" in swift)
        assertEquals(Curtains.KEYS.flatMap { it.toList() }, numbers(after("static let keys: [[Double]] = ").substringBefore("\n")))
        assertEquals(Curtains.WIDTH, swiftValue("static let width = "), 0.0)
        assertEquals(Curtains.TOP, swiftValue("static let top = "), 0.0)
        assertEquals(Curtains.STILL, swiftValue("    static let still: Double = "), 0.0)
        assertEquals(Curtains.STREAK_PITCH, swiftValue("static let streakPitch: Double = "), 0.0)
        assertEquals(Curtains.STREAK_BARS.flatMap { listOf(it.first, it.second) }, numbers(after("static let streakBars: [(x: Double, width: Double)] = ").substringBefore("\n")))
        assertEquals(Curtains.STREAK_BASE, swiftValue("static let streakBase = "), 0.0)
        assertEquals(Curtains.STREAK_BAR, swiftValue("static let streakBar = "), 0.0)
        assertEquals(Curtains.HEM_TOP, swiftValue("static let hemTop = "), 0.0)
        assertEquals(Curtains.KAPPA, swiftValue("static let kappa = "), 0.0)
        val stars = numbers(after("static let all: [(x: Double, y: Double, radius: Double, alpha: Double)] = [").substringBefore("    ]"))
        assertEquals(Stars.ALL.flatMap { listOf(it.x, it.y, it.radius, it.alpha) }, stars)
        val majors = Regex(""""((?:[^"\\]|\\u\{[0-9A-F]+\})+)": \(([0-9.]+), ([0-9.]+), ([0-9]+), (true|false)\)""")
            .findAll(after("static let majors: [String: (Double, Double, Int, Bool)] = [").substringBefore("    ]")).toList()
        assertEquals(5, majors.size)
        for (m in majors) {
            val glyph = unescape(m.groupValues[1])
            val i = CurrencyField.SIGNS.indexOf(glyph)
            val f = CurrencyField.FLOATERS[i]
            assertEquals(glyph, m.groupValues[2].toDouble(), f.bx, 0.0)
            assertEquals(glyph, m.groupValues[3].toDouble(), f.by, 0.0)
            assertEquals(glyph, m.groupValues[4].toInt(), f.size)
            assertEquals(glyph, m.groupValues[5] == "true" || i % 5 == 0, f.cyan)
            assertEquals(glyph, 1.0, f.depth, 0.0)
        }
        val signsStill = numbers(after("static let majors: [String: (Double, Double, Int, Bool)] = [").substringAfter("static let still: Double = ").substringBefore("\n")).first()
        assertEquals(CurrencyField.STILL, signsStill, 0.0)
        assertEquals(AddButtonMotion.TURN_DEGREES.toDouble(), swiftValue("static let turnDegrees: Double = "), 0.0)
        assertEquals(AddButtonMotion.TURN_DAMPING.toDouble(), swiftValue("static let turnDamping = "), 1e-6)
        val response = swiftValue("static let turnResponse = ")
        assertEquals("stiffness = (2 pi / response) squared", (2 * Math.PI / response).let { it * it }, AddButtonMotion.TURN_STIFFNESS.toDouble(), 0.05)
    }

    private fun src(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    @Test
    fun the_renderer_is_throttled_and_pausable() {
        val motion = src("ThemeMotion.kt")
        // D-243 sabotage (A17, A19 survived): the hero's still frame is its still layout, and the hero's cache refills
        // only when it is stale (else a Brush and a Path per frame, against decision 79).
        assertTrue("hero runes: the still layout when still",
            "val p = if (still) HeroRunes.still(i, size.width, size.height) else HeroRunes.pose(i, t, size.width, size.height)" in motion)
        assertTrue("the hero cache refills only when stale", "fun DrawScope.fill() {\n                if (!cache.stale(this, shape)) return" in motion)
        assertTrue("throttled to the clock", "val INTERVAL_NANOS: Long = (1e9 / FPS).toLong()" in motion)
        assertTrue("the loop asks the gate", "val gate = FrameGate()" in motion && "if (gate.due(now)) t = now / 1e9" in motion)
        assertTrue("the frame loop runs per tick", "LaunchedEffect(tick) {\n        if (!tick) return@LaunchedEffect" in motion)
        assertTrue("the runes' still rule reads both inputs", "RuneLayer.isStill(motion.reduce, motion.lowPower)" in motion)
        for (input in listOf(".semantics", "Semantics(", ".pointerInput", ".clickable", ".combinedClickable", "Modifier.clip(", ".clip(")) {
            assertFalse("the art is decoration: no $input", input in motion)
        }
        // F7: the content below the art gets its own layer; nothing allocated per frame in the curtains.
        assertTrue("page art layer", "            Ground.GRID -> Modifier.gridBackground(dark, bleed)\n        },\n    )\n}.graphicsLayer()" in motion)
        assertTrue("hero art layer", "    }.graphicsLayer()\n}" in motion)
        val bodies = motion.substringAfter("private fun DrawScope.curtainBodies(").substringBefore("private fun DrawScope.fillHem(")
        for (alloc in listOf("Matrix()", "Path()", "Brush.")) assertFalse("no $alloc per frame", alloc in bodies)
        // R5: the drawn outline is the tested one: hemPath walks Curtains.hemCurves, the cache keeps hemPath's
        // outlines, and both fills (the base and the bars) draw the cached hem.
        val hemPath = motion.substringAfter("fun hemPath(w: Float, h: Float): Path = Path().apply {").substringBefore("\n    }\n")
        assertTrue("hemPath walks the tested curves", "for (k in Curtains.hemCurves(w.toDouble(), h.toDouble())) {" in hemPath)
        assertTrue("and draws each as a cubic", "cubicTo(k[0].toFloat(), k[1].toFloat(), k[2].toFloat(), k[3].toFloat(), k[4].toFloat(), k[5].toFloat())" in hemPath)
        assertTrue("the cache keeps hemPath's outlines", "hems = Curtains.SPECS.map { spec -> hemPath(rw, scope.size.height * spec.height.toFloat()) }" in motion)
        assertTrue("the curtain takes its cached hem", "val hem = cache.hems[i]" in bodies)
        assertEquals("both fills draw the hem", 2, bodies.split("fillHem(hem, shading,").size - 1)
        assertTrue("the fill draws that path", "drawIntoCanvas { it.drawPath(hem, paint) }" in motion)
        // F10: the art is clipped, the content is not. D-243 (amended on purpose, 3 -> 2): Norse's hero clips to its
        // cached notch (`clipPath(cache.notch)`, no Path per frame) instead of clippedTo; Aurora's and Vardr Labs' keep it.
        assertEquals(2, motion.split("clippedTo(shape) {").size - 1)
        val norse = motion.substringAfter("        AppTheme.NORSE -> {").substringBefore("        AppTheme.VARDR -> {")
        assertEquals("the gradient, the art and the frame clip to the cached notch", 3, norse.split("clipPath(cache.notch) {").size - 1)
        // D-243: nothing is measured or allocated per frame in the rune layers (decision 79): the runes' draws, the hero's
        // art lambda (its runes clipped to the interior, its ᚠ) and the shared rune draw take cached glyphs and brushes.
        val lambda = norse.substringAfter(".artCanvas(0.0, motion) { t ->").substringBefore("                .graphicsLayer()")
        assertTrue("the hero's art lambda is found", "with(ArtDraw) { heroRunes(t, still, cache.runes) }" in lambda)
        assertTrue("its runes inside the inner frame", "clipRect(cache.interior.left, cache.interior.top, cache.interior.right, cache.interior.bottom) {" in lambda)
        val draws = listOf("fun DrawScope.runes(", "fun DrawScope.heroRunes(", "private fun DrawScope.glowingRune(")
            .map { motion.substringAfter(it).substringBefore("\n    }\n") }
        for (body in draws + lambda) {
            // D-246: nor a glow mask (GlyphGlow builds once, in composition), nor a blur of any kind.
            for (alloc in listOf("Path()", "Brush.", "measurer.measure(", "rememberTextMeasurer(", "GlyphGlow.mask(", "BlurMaskFilter", "BlurEffect", "RenderEffect")) {
                assertFalse("no $alloc per frame", alloc in body)
            }
        }
        assertTrue("the hero is one layer between the still gradient and the still frame",
            ".graphicsLayer()\n                .artCanvas(0.0, motion) { t ->" in norse && "                .graphicsLayer()\n                .drawBehind {\n                    clipPath(cache.notch) {" in norse)
        // D-243: the ring's turn (artTurn) runs on the same gate and clock, from upright each start, in the layer alone.
        val turn = motion.substringAfter("fun Modifier.artTurn(state: MotionState? = null, degrees: (Double) -> Double): Modifier = composed {").substringBefore("\n}\n")
        assertTrue("the art's rule", "val tick = (state ?: rememberMotionState()).tick" in turn)
        assertTrue("from upright each start", "LaunchedEffect(tick) {\n        turning = RingTurn.STILL\n        if (!tick) return@LaunchedEffect" in turn)
        assertTrue("the loop asks the gate", "val gate = FrameGate()" in turn && "if (gate.due(now)) turning = now / 1e9 - start" in turn)
        assertTrue("layer-only", "graphicsLayer { rotationZ = degrees(ArtCanvas.seconds(tick, turning, RingTurn.STILL)).toFloat() }" in turn)
        assertTrue("pauses through shouldTick", "MotionClock.shouldTick(" in motion)
        assertTrue("power save read", "isPowerSaveMode" in motion)
        assertTrue("animator scale read", "ANIMATOR_DURATION_SCALE" in motion)
        assertTrue("paused when not resumed", "Lifecycle.State.RESUMED" in motion)
        val aurora = src("Aurora.kt")
        assertTrue("the ground draws the page art", "pageArt(" in aurora)
        assertFalse("the blooms are gone", "rememberInfiniteTransition" in aurora)
        assertFalse("the glow is gone", "glowBackground" in aurora)
    }

    /** The renderer wakes on every vsync; on any display it draws at most 30 times a second on average and at least
     *  24 (F3, R2: odd rates such as 61 and 62.5 Hz included), and a late frame is never followed by a burst. */
    @Test
    fun the_frame_clock_is_throttled_to_thirty_a_second_on_any_display() {
        for (hz in listOf(60.0, 61.0, 62.5, 90.0, 100.0, 120.0, 144.0, 165.0)) {
            val gate = FrameGate()
            val step = 1e9 / hz
            var drawn = 0
            for (frame in 1..(hz * 10).toInt()) if (gate.due((frame * step).toLong())) drawn++
            val fps = drawn / 10.0
            assertTrue("fps at $hz Hz is $fps", fps <= 30.0 && fps >= 24.0)
        }
        // 60 Hz with a 100 ms stall: after the late frame the next kept frame is a whole interval later.
        val gate = FrameGate()
        val kept = mutableListOf<Long>()
        for (frame in 1..600) {
            if (frame in 31..36) continue
            val now = frame * 16_666_667L
            if (gate.due(now)) kept += now
        }
        val gaps = kept.zipWithNext { a, b -> b - a }
        assertTrue("no burst: the shortest gap is ${gaps.min()}", gaps.min() >= MotionClock.INTERVAL_NANOS - MotionClock.SLACK_NANOS)
        assertFalse(FrameGate().let { it.due(1_000_000_000L); it.due(1_000_000_000L + 10_000_000L) })
    }

    /** P2 (fix wave 3): every art in a window keeps the SAME vsyncs, so two arts together draw 30 frames a second, not
     *  60 on alternating vsyncs: two gates started at different vsyncs (and phases of the display against the grid) on
     *  one vsync stream keep the same set from the later one's start on, with a stall in the stream too; and the
     *  window as a whole (the union) never draws more than one gate does. */
    @Test
    fun every_art_in_a_window_ticks_on_the_same_vsyncs() {
        for (hz in listOf(60.0, 61.0, 90.0, 120.0, 144.0)) {
            for (phase in listOf(0L, 4_000_000L, 9_000_000L, 13_500_000L)) {
                for (start in listOf(2, 3, 5, 8, 13)) {
                    for (stall in listOf(false, true)) {
                        val a = FrameGate()
                        val b = FrameGate()
                        val keptA = mutableListOf<Long>()
                        val keptB = mutableListOf<Long>()
                        val step = 1e9 / hz
                        for (frame in 1..(hz * 10).toInt()) {
                            if (stall && frame in 40..45) continue
                            val now = 1_000_000_000L + phase + (frame * step).toLong()
                            if (a.due(now)) keptA += now
                            if (frame >= start && b.due(now)) keptB += now
                        }
                        val joined = 1_000_000_000L + phase + (start * step).toLong()
                        assertEquals("$hz Hz, phase $phase, b from vsync $start, stall $stall", keptA.filter { it > joined }, keptB)
                        assertEquals("the window draws what one art draws", keptA.size, (keptA + keptB).toSet().size)
                    }
                }
            }
        }
        // The grid is the frame clock's own: the same vsync lands in the same slot for every gate.
        assertEquals(FrameGate.slot(1_000_000_000L), FrameGate.slot(1_000_000_000L))
        assertEquals(30L, FrameGate.slot(1_000_000_000L))
    }

    /** P1 (fix wave 3): the page art reaches under the system bars, the twin of iOS's `.ignoresSafeArea()`: an edge of
     *  the page within the window's safe-drawing inset on its side goes out to the window's edge; any other edge stays. */
    @Test
    fun the_page_art_reaches_under_the_system_bars() {
        val window = androidx.compose.ui.geometry.Size(1080f, 2400f)
        fun rect(l: Float, t: Float, r: Float, b: Float) = androidx.compose.ui.geometry.Rect(l, t, r, b)
        // A page inside the safe-drawing padding (status bar 63 px, gesture bar 63 px): out to both edges.
        assertEquals(Bleed(0f, 63f, 0f, 63f), Bleed.of(rect(0f, 63f, 1080f, 2337f), window, 0f, 63f, 0f, 63f))
        // A cutout on the left in landscape: out to the left edge too.
        assertEquals(Bleed(90f, 0f, 0f, 0f), Bleed.of(rect(90f, 0f, 1080f, 2400f), window, 90f, 0f, 0f, 0f))
        // A page below a header, above a tab bar: no edge on a bar, nothing changes.
        assertEquals(Bleed.NONE, Bleed.of(rect(0f, 300f, 1080f, 2100f), window, 0f, 63f, 0f, 63f))
        // A page already at the window's edges (no insets, or a dialog's own window): nothing changes.
        assertEquals(Bleed.NONE, Bleed.of(rect(0f, 0f, 1080f, 2400f), window, 0f, 63f, 0f, 63f))
        assertEquals(Bleed.NONE, Bleed.of(rect(40f, 500f, 1040f, 1500f), window, 0f, 0f, 0f, 0f))
        // Every ground draws through the bleed: the curtains and their sky (night, dawn), the runes and their ground,
        // and the grid; and the page art measures its node on the window.
        val motion = src("ThemeMotion.kt")
        val page = motion.substringAfter("fun Modifier.pageArt(dark: Boolean): Modifier = composed {").substringBefore("}.graphicsLayer()")
        assertTrue("the page measures itself", "onGloballyPositioned { c ->" in page && "Rect(c.positionInRoot(), c.size.toSize()), root.size.toSize()," in page)
        assertTrue("against the safe-drawing insets", "val insets = WindowInsets.safeDrawing" in page)
        // D-226: the curtains' sky, curtains and stars are one drawing (auroraArt), recorded for the glass or drawn, bled.
        val curtains = motion.substringAfter("private fun Modifier.curtainsGround(").substringBefore("\n}\n")
        assertTrue("curtains", "val b = bleed.value\n            bled(b) {" in curtains)
        assertEquals("the art drawn or recorded, bled", 2, curtains.split("auroraArt(t, dark, curtainStrength, starStrength, layer, cache)").size - 1)
        val art = motion.substringAfter("private fun DrawScope.auroraArt(").substringBefore("\n}\n")
        assertTrue("night and dawn", "if (dark) nightSkyBody() else dawnBody()" in art)
        assertTrue("then the curtains", "with(ArtDraw) { curtains(t, dark, curtainStrength, starStrength, layer, cache) }" in art)
        // D-243 (amended on purpose): the runes take their cached glyphs and (D-246) glows (`glows`; D-243's `halos`),
        // not a measurer.
        assertTrue("runes", "bled(bleed.value) { with(ArtDraw) { runes(t, still, strength, glows) } }" in motion)
        assertTrue("the runes' ground", "drawBehind {\n        bled(bleed.value) {\n            drawRect(bg)" in motion)
        assertTrue("bled grows the node", "inset(-b.left, -b.top, -b.right, -b.bottom, block)" in motion)
        assertTrue("the grid", "inset(-b.left, -b.top, -b.right, -b.bottom) {" in src("Aurora.kt"))
    }

    /** The time the engine is handed is always finite (Curtains.pose truncates to an integer): twin of iOS. */
    @Test
    fun the_renderer_time_is_always_finite() {
        assertEquals(12.5, ArtCanvas.seconds(tick = true, now = 12.5, still = 3.0), 0.0)
        assertEquals(3.0, ArtCanvas.seconds(tick = false, now = 12.5, still = 3.0), 0.0)
        assertEquals(3.0, ArtCanvas.seconds(tick = true, now = Double.NaN, still = 3.0), 0.0)
        assertEquals(3.0, ArtCanvas.seconds(tick = true, now = Double.POSITIVE_INFINITY, still = 3.0), 0.0)
        assertEquals(0.0, ArtCanvas.seconds(tick = false, now = 1.0, still = Double.NaN), 0.0)
        for (t in listOf(ArtCanvas.seconds(tick = true, now = System.nanoTime() / 1e9, still = 0.0), 0.0, 1e9)) {
            for (i in 0..2) assertTrue(Curtains.pose(i, t).x.isFinite())
        }
    }

    /** The runes show their still layout exactly when the motion inputs are off, not when only the screen is. */
    @Test
    fun the_runes_are_still_under_reduce_motion_or_low_power_only() {
        assertTrue(RuneLayer.isStill(reduceMotion = true, lowPower = false))
        assertTrue(RuneLayer.isStill(reduceMotion = false, lowPower = true))
        assertFalse(RuneLayer.isStill(reduceMotion = false, lowPower = false))
    }

    /** The rune colours (the Look Book's `.runefall span` gold and `span.s` silver, made vibrant by D-242: motion.py's
     *  GOLD and SILVER), not D-213's ThemeArt tokens. */
    @Test
    fun the_runes_are_the_look_books_gold_and_silver() {
        val motion = src("ThemeMotion.kt")
        assertTrue("gold", "0xFFDFB043" in motion)
        assertTrue("silver", "0xFFB8C0CC" in motion)
        assertFalse("not the D-213 tokens", "diamondTop" in motion)
        // D-226: the one D-213 colour ThemeMotion.kt names is the silver page glow's (art.py 48: the Look Book's own
        // rgba(142,147,155) was norseSilver until D-242, which made norseSilver #9AA2AE), once, in NorseGlow's silver
        // row; the runes' drawing never takes it.
        assertEquals("norseSilver once", 1, motion.split("norseSilver").size - 1)
        assertTrue("NorseGlowSpec(ThemeArt.color(\"norseSilver\"), 0.07," in motion.substringAfter("object NorseGlow {"))
        assertFalse("not the runes' colour", "norseSilver" in motion.substringAfter("object ArtDraw {"))
    }

    /** D-214 (the founder, 2026-10-15): faint and accent text sitting directly on the page ground takes the ground
     *  tokens. The counts are pinned after the site-by-site classification recorded in BUILD-LOG D-214: a new site
     *  that sits on the ground must take the token and move its count on purpose. Android's own (iOS: 14 and 3;
     *  Android draws its own Back and Cancel buttons, iOS's bar items take the token through `barTint()`). */
    @Test
    fun ground_text_takes_the_ground_tokens() {
        val files = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.extension == "kt" }!!
        fun count(needle: String) = files.sumOf { it.readText().split(needle).size - 1 }
        // D-217: 11, the Licences row's chevron (iOS's LicenceRow had one, Android's did not).
        // D-217 T6 fix: 14 (iOS's own count), the expense detail's three ground kickers (category, "Paid by", the
        // footer line) take it as iOS's `micro(..., ground: true)` does.
        // D-225 T5: 11, the Licences row, People's member chevron and the remove cross sit in list cards and take
        // `textDim` / `textFaint` (the card's tokens), as iOS's list rows do.
        // D-225 fix wave A3 (decision 26): 9, Settle all's "Fastest way" and a redistribute section's currency code are
        // section labels and take `textDim` (no_section_label_anywhere_takes_a_faint_colour).
        // D-248 (U29.4): 10, Add expense's "0" hint on the page ground (it was textFaint at 45%, about 2:1 in Norse).
        // D-249: 11, the expense detail's "Only you. Nothing to split." for a Just me expense (iOS's `micro(..., ground: true)`).
        assertEquals(11, count("Tok.groundFaint("))
        // D-217 Task 6: 16, the group page's and expense detail's "Back" text buttons (two) are the bar's round button now.
        // D-217 Task 7: 6, nine "Back" text buttons (About x3, You, Activity, Export, Profile, the waiting expense,
        // ScreenHeader's) are the bar's round button, and the dead group list's check mark went with it; the six left
        // are the add form's Cancel and the other ground accents.
        // D-217 Task 8: 4, the add form's Cancel and the username screen's Cancel text buttons are the sheet bar's pills now.
        // D-217 final fix: 5, the sheet bar's pills take it as iOS's bar items do (`.barTint()`, BarTint.token).
        // D-221: 4, You's "Edit" link went (Q11 a: the progress pill takes its place).
        // D-223 A1: 5, Lately's "See all", the theme's link as the controller's amendment names it (iOS's twin takes
        // `Tokens.groundAccent` too).
        // D-223 fix wave (decision 21): 4, "See all" sits on the card's surface and takes `Tok.accent`, as every in-card
        // link does ("People in X").
        // D-225 T5: 3, People's "Invite X" rows are list rows in a card (iOS's twin draws them in `text` with a chevron).
        // D-245: the welcome's Skip, its back chevron (decision 198) and its "Welcome to" lead sit on the ground: + 3
        // (iOS's twin went 2 to 5).
        assertEquals(6, count("Tok.groundAccent("))
    }

    @Test
    fun the_heroes_draw_behind_their_content() {
        assertTrue("fun Modifier.heroArt(dark: Boolean, shape: Shape)" in src("ThemeMotion.kt"))
        // D-220 fix wave (I3): the hero cards take the theme's card shape (Norse's notch, Vardr Labs' square).
        assertTrue(".heroArt(dark, ThemeArt.cardShape(Appearance.theme))" in src("OverviewTab.kt"))
        assertTrue(".heroArt(dark, ThemeArt.cardShape(Appearance.theme))" in src("SignInScreen.kt"))
        assertFalse("the Overview hero no longer paints the flat brush", ".background(heroBrush(dark)" in src("OverviewTab.kt"))
        assertFalse("the sign-in hero no longer paints the flat brush", ".background(heroBrush(dark)" in src("SignInScreen.kt"))
    }

    /** F6: the bolt's turn rides its own spring, the twin of iOS's (response 0.45 s, damping 0.55), and never runs
     *  under "remove animations". */
    @Test
    fun the_bolt_turns_in_the_tab_bar() {
        assertEquals(0.55f, AddButtonMotion.TURN_DAMPING, 0f)
        assertEquals(195f, AddButtonMotion.TURN_STIFFNESS, 0f)
        val bar = src("TabBar.kt")
        assertTrue(bar.contains("AddButtonMotion.turn(AddButtonArt.of(Appearance.theme), pressed, reduce),"))
        assertTrue(bar.contains("val reduce = rememberMotionState().reduce"))
        assertTrue(bar.contains("spring(dampingRatio = AddButtonMotion.TURN_DAMPING, stiffness = AddButtonMotion.TURN_STIFFNESS), label = \"turn\""))
        // S2 (fix wave 3): the animated turn itself feeds the face's rotation (a constant there would pass the lines above).
        assertTrue(bar.contains("val turn by animateFloatAsState(\n                        AddButtonMotion.turn("))
        assertTrue(bar.contains(".graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = turn },"))
        assertEquals("one rotation in the bar", 1, bar.split("rotationZ").size - 1)
    }

    /** D-215: the Norse hero's watermark is Fehu (U+16A0) in Vardr Runes Carved, the face every rune takes (iOS draws the
     *  same shape from the same file), at `heroWatermark` dp (D-226: 118) through the density: no font family of its own,
     *  no system face. */
    @Test
    fun the_norse_watermark_is_fehu_in_vardr_runes() {
        assertEquals("\u16A0", Runes.BALANCE)
        val norse = src("ThemeMotion.kt").substringAfter("        AppTheme.NORSE -> {").substringBefore("        AppTheme.VARDR -> {")
        // D-243 (amended on purpose): measured once in composition (the hero's cache), through the density, not per draw.
        assertTrue(norse.contains("val glyph = with(density) { measurer.measure(Runes.BALANCE, Type.rune(ThemeArt.m(\"heroWatermark\").dp.toSp())) }"))
        // D-246 (amended on purpose): the cache also holds the ᚠ's glow, GlyphGlow's mask, through the rune raster.
        assertTrue("held by the cache", "ArtDraw.NorseHeroCache(ArtDraw.RuneGlowCache.hero(measurer, density, raster), glyph, fehuGlow)" in norse)
        assertTrue("its glow", "val fehuGlow = GlyphGlow.mask(Runes.BALANCE, ThemeArt.m(\"heroWatermark\").toDouble(), RuneGlow.BLUR, raster)" in norse)
        // D-243 fix wave (M6): the cache is keyed on the colours its gradient and tint (D-246; D-243's halo brush) are
        // filled from, and (D-246) on the raster.
        assertTrue("keyed on its colours", "val cache = remember(measurer, density, raster, start, end, accent) {" in norse)
        assertTrue("drawn from it", "val glyph = cache.fehu" in norse)
        assertFalse("no font of its own", norse.contains("fontFamily"))
        assertTrue("the tab bar's runes take the same face", src("TabBar.kt").contains("style = Type.rune(runeSize(\"tabRune\", LocalDensity.current)).copy(color = tint),"))
    }

    /** D-221 (Design 12, motion.py part 5): the timeline the page and the words follow. Moving: the content dims to
     *  DIM_TO over DIM_IN and comes back over the last DIM_IN; the words fade in over MESSAGE_IN rising MESSAGE_RISE and
     *  undo the same way over the last MESSAGE_IN. Still: dimmed and shown, without motion, for TOTAL. */
    @Test
    fun the_celebration_timeline() {
        fun dim(e: Double, still: Boolean = false) = CelebrationTimeline.dim(e, still).toDouble()
        assertEquals(1.0, dim(0.0), 1e-6)
        assertEquals(0.61, dim(0.15), 1e-6)
        assertEquals(0.22, dim(0.3), 1e-6)
        assertEquals(0.22, dim(1.8), 1e-6)
        assertEquals(0.22, dim(3.3), 1e-6)
        assertEquals(0.61, dim(3.45), 1e-6)
        assertEquals(1.0, dim(3.6), 1e-6)
        assertEquals(1.0, dim(9.0), 1e-6)
        assertEquals("still: dimmed from the start", 0.22, dim(0.0, still = true), 1e-6)
        assertEquals(0.22, dim(3.59, still = true), 1e-6)
        assertEquals(1.0, dim(3.6, still = true), 1e-6)
        fun msg(e: Double, still: Boolean = false) = CelebrationTimeline.message(e, still).let { listOf(it.alpha.toDouble(), it.rise.toDouble()) }
        fun near(want: List<Double>, got: List<Double>) = assertTrue("$want vs $got", want.zip(got).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6 })
        near(listOf(0.0, 10.0), msg(0.0))
        near(listOf(0.5, 5.0), msg(0.2))
        near(listOf(1.0, 0.0), msg(0.4))
        near(listOf(1.0, 0.0), msg(2.0))
        near(listOf(0.5, 5.0), msg(3.4))
        near(listOf(0.0, 10.0), msg(3.6))
        near(listOf(1.0, 0.0), msg(0.0, still = true))
        near(listOf(1.0, 0.0), msg(3.59, still = true))
        near(listOf(0.0, 0.0), msg(3.6, still = true))
    }

    /** D-221 (L45): the burst draws each theme's particle from SettledBurst's poses at the page's (ORIGIN_X, ORIGIN_Y):
     *  Aurora a dot with its glow, Norse a rune of the Futhark through the rune face and the TextMeasurer path the
     *  runes use, Vardr Labs a square; scaled and faded by the pose. */
    @Test
    fun the_burst_draws_each_themes_particle() {
        val burst = src("ThemeMotion.kt").substringAfter("fun DrawScope.burst(elapsed: Double, theme: AppTheme, color: Color, runes: List<TextLayoutResult>) {").substringBefore("\n}\n")
        assertTrue("the burst is drawn", burst.isNotEmpty() && burst.length < 4000)
        assertTrue("nothing before the start", "if (!elapsed.isFinite() || elapsed < 0.0) return" in burst)
        assertTrue("every particle's pose", "for (i in 0 until SettledBurst.COUNT)" in burst && "SettledBurst.pose(i, elapsed, width) ?: continue" in burst)
        assertTrue("from the origin", "SettledBurst.ORIGIN_X" in burst && "SettledBurst.ORIGIN_Y" in burst)
        assertTrue("the width in points", "val width = (size.width / density).toDouble()" in burst)
        assertTrue("Aurora's dot and glow", "SettledBurst.AURORA_DOT" in burst && "SettledBurst.AURORA_GLOW" in burst)
        assertTrue("Norse's rune of the Futhark", "runes.getOrNull(i % Runes.FUTHARK.size)?.let { burstRune(it, at, scale, alpha, color) }" in burst)
        assertTrue("in the rune face", "measurer.measure(it, Type.rune(with(density) { SettledBurst.NORSE_RUNE.toFloat().dp.toSp() }))" in burst)
        assertTrue("Vardr Labs' square", "SettledBurst.VARDR_SQUARE" in burst)
        assertTrue("faded by the pose", "val alpha = p.opacity.toFloat()" in burst)
        assertEquals("three themes", 3, listOf("AppTheme.AURORA ->", "AppTheme.NORSE ->", "AppTheme.VARDR ->").count { it in burst })
        assertEquals(24, Runes.FUTHARK.size)
    }

    /** D-226 (Q7 a, L33): the stars draw OVER the curtains, on the page and the hero: the curtain layer first, then the
     *  eight stars source-over. The twin of iOS's testStarsDrawOverTheCurtains. */
    @Test
    fun stars_draw_over_the_curtains() {
        val draw = src("ThemeMotion.kt").substringAfter("    fun DrawScope.curtains(").substringBefore("\n    }\n")
        val layer = draw.indexOf("drawLayer(layer)")
        val stars = draw.indexOf("for (s in Stars.ALL)")
        assertTrue("both are drawn: $layer, $stars", layer >= 0 && stars >= 0)
        assertTrue("the stars after the curtains", stars > layer)
        assertEquals("one star loop", 1, draw.split("for (s in Stars.ALL)").size - 1)
    }

    /** D-226 (L34, art.py section 48): Norse's page draws its two glows over `bg` and under the runes, from NorseGlow's
     *  table at `norsePageGlow`. The twin of iOS's testTheNorseGroundHasItsGlows. */
    @Test
    fun the_norse_ground_has_its_glows() {
        val runes = src("ThemeMotion.kt").substringAfter("private fun Modifier.runesGround(").substringBefore("\n}\n")
        val bg = runes.indexOf("drawRect(bg)")
        val glows = runes.indexOf("for (g in NorseGlow.SPECS)")
        // D-243 (amended on purpose by D-246): the cached glyphs and glows (D-243's halos).
        val drawn = runes.indexOf("runes(t, still, strength, glows)")
        assertTrue("laid out once", "val glows = remember(measurer, density, raster) { ArtDraw.RuneGlowCache.page(measurer, density, raster) }" in runes)
        assertTrue("all three: $bg, $glows, $drawn", bg >= 0 && glows >= 0 && drawn >= 0)
        assertTrue("the glows over the ground", glows > bg)
        assertTrue("and under the runes", glows < drawn)
        assertTrue("at the founder's strength", "Strength.of(\"norsePageGlow\")" in runes)
        assertTrue("each alpha times it", "g.color.copy(alpha = (g.alpha * glow).toFloat())" in runes)
        assertTrue("by the dawn's technique", "scale(size.width * g.radiusX.toFloat(), size.height * g.radiusY.toFloat(), Offset.Zero)" in runes)
        assertTrue("clear at its stop", "radius = g.clearAt.toFloat()" in runes)
    }

    /** D-226 (L31, art.py section 49): Norse's hero is the Look Book's two stops at CSS 160 degrees for its own size, with
     *  the watermark and the inner frame from the table (no literal left). The twin of iOS's
     *  testTheNorseHeroIsTwoStopsAt160. */
    @Test
    fun the_norse_hero_is_two_stops_at_160() {
        val norse = src("ThemeMotion.kt").substringAfter("        AppTheme.NORSE -> {").substringBefore("        AppTheme.VARDR -> {")
        assertTrue("the angle", "CssGradient.unitPoints(160" in norse)
        assertTrue("two stops", "listOf(start, end)" in norse && "val start = Tok.heroStart(dark)" in norse && "val end = Tok.heroEnd(dark)" in norse)
        assertFalse("not the three-stop hero brush", "heroBrush(" in norse || "base" in norse)
        for (key in listOf("heroWatermark", "heroWatermarkTrailing", "heroWatermarkTop", "heroFrameInset")) {
            assertTrue(key, "ThemeArt.m(\"$key\").dp" in norse)
        }
        for (literal in listOf("92.dp", "5.dp", "10.dp", "-6.dp")) assertFalse("no $literal", literal in norse)
        // WORKLOG decision 29: its edge on the straight sides, as every Norse card's; no whole outline.
        assertEquals(1, norse.split(".notchEdge(ThemeArt.color(\"norseEdge\"), ThemeArt.m(\"chamfer\"))").size - 1)
        assertFalse("no whole outline", ".border(" in norse)
    }

    // ---- D-231 Task 1: the Less motion setting (decisions 68 to 70), the twin of iOS's ThemeMotionTests ----

    /** Nothing stored reads as the low-memory default; a stored value, either way, wins. */
    @Test
    fun the_less_motion_setting_parses() {
        assertEquals("lessMotion", MotionSetting.STORAGE_KEY)
        assertFalse("never set, memory to spare: off", MotionSetting.parse(null, lowRam = false))
        assertTrue("never set, a low-memory phone: on", MotionSetting.parse(null, lowRam = true))
        assertTrue("stored on", MotionSetting.parse(true, lowRam = false))
        assertFalse("a stored off beats the low-memory default", MotionSetting.parse(false, lowRam = true))
    }

    /** Under 3 GiB, or the platform's flag. 3 GiB re-derived as 3 x 1024^3 = 3_221_225_472, not read back. */
    @Test
    fun a_low_memory_phone_is_under_three_gib() {
        val threeGiB = 3_221_225_472L
        assertEquals(threeGiB, MotionSetting.LOW_RAM_BYTES)
        assertTrue("one byte under", MotionSetting.lowRam(threeGiB - 1, lowRamFlag = false))
        assertFalse("exactly 3 GiB is not low", MotionSetting.lowRam(threeGiB, lowRamFlag = false))
        assertFalse("6 GiB", MotionSetting.lowRam(6_442_450_944L, lowRamFlag = false))
        assertTrue("2 GiB", MotionSetting.lowRam(2_147_483_648L, lowRamFlag = false))
        assertTrue("the platform's flag wins at 8 GiB", MotionSetting.lowRam(8_589_934_592L, lowRamFlag = true))
    }

    /** The switch only adds stillness: the four rows of OR. */
    @Test
    fun less_motion_only_adds_stillness() {
        assertFalse("both off: motion", MotionSetting.reduced(system = false, lessMotion = false))
        assertTrue("the switch alone", MotionSetting.reduced(system = false, lessMotion = true))
        assertTrue("the phone's remove animations, switch off", MotionSetting.reduced(system = true, lessMotion = false))
        assertTrue("both on", MotionSetting.reduced(system = true, lessMotion = true))
    }

    /** Parity: ThemeMotion.swift's key is `appearance.` + Kotlin's (iOS prefixes its appearance keys in the shared
     *  defaults; Android keeps them unprefixed in their own file, as `haptics`), its 3 GiB bound is the same expression,
     *  and its `MotionCopy` is Kotlin's word for word. */
    @Test
    fun the_less_motion_setting_matches_ios() {
        val setting = swift.substringAfter("enum MotionSetting {").substringBefore("\n}\n")
        assertTrue("iOS has MotionSetting", setting.length < swift.length)
        assertTrue("the key", "static let storageKey = \"appearance.${MotionSetting.STORAGE_KEY}\"" in setting)
        assertTrue("the bound", "static let lowRamBytes: UInt64 = 3 * 1024 * 1024 * 1024" in setting)
        val copy = swift.substringAfter("enum MotionCopy {").substringBefore("\n}\n")
        assertTrue("iOS has MotionCopy", copy.length < swift.length)
        val ios = Regex("\"([^\"]*)\"").findAll(copy).map { it.groupValues[1] }.toList()
        assertEquals(listOf(MotionCopy.TITLE, MotionCopy.SUMMARY), ios)
    }

    /** The row's words, verbatim; no dash. */
    @Test
    fun the_less_motion_copy() {
        assertEquals("Less motion", MotionCopy.TITLE)
        assertEquals("Still art, fewer animations", MotionCopy.SUMMARY)
        for (s in listOf(MotionCopy.TITLE, MotionCopy.SUMMARY)) {
            assertFalse("no dash in $s", "\u2014" in s || "\u2013" in s)  // em-dash-ok: the escapes build the dashes this test asserts are absent
        }
    }

    // ---- D-231 Task 3: Android's one door (decision 71) ----

    /** The combine is pure: Less motion alone stills the art with the phone's "remove animations" off. */
    @Test
    fun less_motion_stills_the_art_with_the_system_off() {
        assertTrue("nothing asks for stillness: ticks", MotionState.of(system = false, lessMotion = false, lowPower = false, resumed = true).tick)
        val switch = MotionState.of(system = false, lessMotion = true, lowPower = false, resumed = true)
        assertTrue("the switch reduces", switch.reduce)
        assertFalse("the switch alone stills the art", switch.tick)
        assertFalse("the phone's setting, switch off", MotionState.of(system = true, lessMotion = false, lowPower = false, resumed = true).tick)
        assertFalse("battery saver is unchanged", MotionState.of(system = false, lessMotion = false, lowPower = true, resumed = true).tick)
    }

    /** Decision 70: `Appearance.lessMotion` is the live switch; `load` reads the stored key through `MotionSetting.parse`,
     *  its default the low-RAM read (the platform's flag and total memory, once per launch); `setLessMotion` stores an
     *  explicit value that wins from then on. */
    @Test
    fun appearance_holds_less_motion() {
        try {
            Appearance.setForTest()
            assertFalse("off unless told", Appearance.lessMotion)
            Appearance.setForTest(lessMotion = true)
            assertTrue(Appearance.lessMotion)
        } finally {
            Appearance.setForTest()
        }
        val app = src("Appearance.kt")
        val load = app.substringAfter("fun load(context: Context) {").substringBefore("\n    }\n")
        assertTrue("load reads the key through parse", "lessMotion = MotionSetting.parse(" in load && "prefs.contains(MotionSetting.STORAGE_KEY)" in load)
        assertTrue("its default is the low-RAM read", "            lowRamDevice(context),\n" in load)
        val set = app.substringAfter("fun setLessMotion(context: Context, on: Boolean) {").substringBefore("\n    }\n")
        // D-244 (ADR-0043), amended on purpose: an explicit value through the one write of a synced setting (the same key,
        // a Boolean), then the live switch from what was stored (Appearance.reload reads it as load does).
        assertTrue("setLessMotion stores through the funnel", "SettingsSync.set(context, SettingsSync.LESS_MOTION, SettingsSync.encode(on))" in set)
        assertEquals(
            SettingsSync.Synced(SettingsSync.LESS_MOTION, SettingsSync.Kind.FLAG, "skipti-appearance", MotionSetting.STORAGE_KEY),
            SettingsSync.entry(SettingsSync.LESS_MOTION),
        )
        assertTrue("the switch follows", "load(context)" in app.substringAfter("fun reload(context: Context, keys: List<String>) {").substringBefore("\n    }\n"))
        val ram = app.substringAfter("private fun lowRamDevice(context: Context): Boolean {").substringBefore("\n    }\n")
        assertTrue("the phone's memory", "ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }" in ram)
        assertTrue("the platform's flag and total memory", "return MotionSetting.lowRam(info.totalMem, am.isLowRamDevice)" in ram)
    }

    /** Decision 71: one door. The phone's "remove animations" is read in ThemeMotion.kt alone, inside
     *  rememberMotionState; every MotionState is built by MotionState.of, which ORs it with Less motion; no other file
     *  builds one or reads the animator scale. */
    @Test
    fun reduce_motion_has_one_door() {
        val files = java.io.File("src/main/java/com/vardrlabs/skipti").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the walk found the app", files.size > 40)
        val ctor = Regex("""(?<![A-Za-z.])MotionState\(""")
        for (f in files.filter { it.name != "ThemeMotion.kt" }) {
            val t = f.readText()
            // Fix wave m6: ValueAnimator.areAnimatorsEnabled() is the same phone setting by another name.
            assertFalse("${f.name} reads the animator scale", "ANIMATOR_DURATION_SCALE" in t || "animatorsOff(" in t || "areAnimatorsEnabled" in t)
            assertFalse("${f.name} builds a MotionState itself", ctor.containsMatchIn(t))
        }
        val motion = src("ThemeMotion.kt")
        assertEquals("the declaration and the one call in MotionState.of", 2, ctor.findAll(motion).count())
        val of = motion.substringAfter("fun of(system: Boolean, lessMotion: Boolean, lowPower: Boolean, resumed: Boolean)").substringBefore("\n    }\n")
        assertTrue("the one combine", "MotionState(MotionSetting.reduced(system, lessMotion), lowPower, resumed)" in of)
        val remember = motion.substringAfter("fun rememberMotionState(): MotionState {").substringBefore("\n}\n")
        assertTrue("the switch joins the phone's input", "return MotionState.of(system, Appearance.lessMotion, lowPower, resumed)" in remember)
        assertEquals("the phone's input read three times, all in rememberMotionState", 3, remember.split("animatorsOff(context)").size - 1)
        assertEquals("and nowhere else in the file but its definition", 4, motion.split("animatorsOff(").size - 1)
        assertFalse("the door reads the scale, never areAnimatorsEnabled", "areAnimatorsEnabled" in motion)
    }

    /** Fix wave I1 (final review): Compose's own animations follow the phone's "remove animations" by themselves, a
     *  second, implicit door the Less motion switch never reaches. So every Compose animation the app draws is gated in
     *  code on [rememberMotionState]: a value animation's spec is `if (reduce) snap() else ...` (or `still`, which is
     *  reduce or low power), an animated scroll sits beside its plain twin under `if (reduce)`, AnimatedVisibility's enter
     *  is None when reduced, and an API this guard has not learned (Crossfade, transitions, Animatable, ...) fails here
     *  until it is gated and taught. Every `reduce` / `still` comes from rememberMotionState. Not caught: an animation
     *  that names none of these APIs (a hand-rolled frame loop outside ThemeMotion.kt's engine). */
    @Test
    fun every_compose_animation_passes_the_door() {
        val files = java.io.File("src/main/java/com/vardrlabs/skipti").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the walk found the app", files.size > 40)
        val valueAnim = Regex("""animate\w*AsState\(""")
        val gate = Regex("""if \((reduce|still)\) snap\(\) else """)
        val scroll = Regex("""animateScroll\w*\(""")
        val unlearned = listOf("Crossfade(", "AnimatedContent(", "updateTransition(", "rememberInfiniteTransition(", "animateContentSize(",
            "Animatable(", "animateDecay(")
        var seen = 0
        for (f in files) {
            val t = f.readText()
            for (m in valueAnim.findAll(t)) {
                if (t.substring(t.lastIndexOf("\n", m.range.first) + 1).startsWith("import ")) continue
                val call = t.substring(m.range.first).substringBefore("label =")
                assertTrue("${f.name}: an ungated ${m.value} (spec must be `if (reduce) snap() else ...`): ${call.take(160)}", gate.containsMatchIn(call))
                seen++
            }
            for (m in scroll.findAll(t)) {
                val line = t.substring(t.lastIndexOf("\n", m.range.first) + 1).substringBefore("\n")
                assertTrue("${f.name}: an animated scroll not beside its plain twin under reduce: $line", "if (reduce) " in line && " else " in line)
                seen++
            }
            for (m in Regex("""AnimatedVisibility\(""").findAll(t)) {
                val call = t.substring(m.range.first).split(Regex("""\n\s*\) \{"""), 2)[0]
                val enters = "if (motion.reduce) EnterTransition.None else" in call || "if (still) EnterTransition.None else" in call
                // D-234 (A2): an exit is allowed when it is gated the same way (a closing must snap too).
                val exits = "exit =" !in call || "exit = if (still) ExitTransition.None else" in call ||
                    "exit = if (motion.reduce) ExitTransition.None else" in call
                assertTrue("${f.name}: AnimatedVisibility enters and exits with nothing when reduced", enters && exits)
                seen++
            }
            for (api in unlearned) assertFalse("${f.name} uses $api: gate it on rememberMotionState and teach this guard", api in t)
            for (m in Regex("""val (reduce|still|motion) = (.+)""").findAll(t)) {
                val ok = when (m.groupValues[1]) {
                    "reduce" -> m.groupValues[2] == "rememberMotionState().reduce"
                    "still" -> m.groupValues[2] in setOf("motion.reduce", "RuneLayer.isStill(motion.reduce, motion.lowPower)")
                    else -> m.groupValues[2] in setOf("rememberMotionState()", "rememberMotionState().moment()", "art.moment()")
                }
                assertTrue("${f.name}: `${m.value}` comes from rememberMotionState", ok)
            }
        }
        // TabBar.kt's two scales, the bolt's turn and the re-tap scroll; SkiptiBar.kt's three fades; ListRows.kt's switch and push.
        // D-234 (A2): + ListRows.kt's disclosure turn and AccountHubScreen.kt's App icon rows and caption.
        // D-234 (L4): + SheetPage's large-title rest.
        // D-245: + the welcome's step fade (Welcome.kt).
        assertEquals("the guard saw every gated animation", 14, seen)
    }

    /** Fix wave I1 (final review): the tab bar holds still under reduced motion (the phone's remove animations OR Less
     *  motion), the twin of iOS's SkiptiTabBar: the selected tab keeps its size (no 1.08), the + keeps its size when
     *  pressed (no 0.9) and its bolt does not turn, all three snap, and a re-tap jumps to the top instead of scrolling.
     *  One read of the door per composable. */
    @Test
    fun the_tab_bar_holds_still_when_motion_is_reduced() {
        val tabBar = src("TabBar.kt")
        val bar = tabBar.substringAfter("internal fun SkiptiTabBar(").substringBefore("\n}\n")
        assertEquals("one read of the door in the bar", 1, bar.split("val reduce = rememberMotionState().reduce").size - 1)
        assertTrue("read before any tab draws", bar.indexOf("val reduce = rememberMotionState().reduce") < bar.indexOf("HomeTab.bar.forEach"))
        assertTrue("the + press", "val scale by animateFloatAsState(if (pressed && !reduce) 0.9f else 1f, if (reduce) snap() else spring(dampingRatio = 0.5f), label = \"add\")" in bar)
        assertTrue("the selected tab", "val scale by animateFloatAsState(if (on && !reduce) 1.08f else 1f, if (reduce) snap() else spring(dampingRatio = 0.55f), label = tab.name)" in bar)
        assertTrue("the bolt's turn", "if (reduce) snap() else spring(dampingRatio = AddButtonMotion.TURN_DAMPING, stiffness = AddButtonMotion.TURN_STIFFNESS), label = \"turn\"" in bar)
        val top = tabBar.substringAfter("fun rememberShellScrollState(state: ScrollState): ScrollState {").substringBefore("\n}")
        assertTrue("the re-tap reads the door", "val reduce = rememberMotionState().reduce" in top)
        assertTrue("and jumps when reduced", "if (ShellScrollTop.scrolls(signal, atEntry)) { if (reduce) state.scrollTo(0) else state.animateScrollTo(0) }" in top)
        // The iOS twins these mirror (SkiptiTabBar.swift).
        val ios = java.io.File("../../ios/Skipti/App/SkiptiTabBar.swift").readText()
        assertTrue(".scaleEffect(on && !reduceMotion ? 1.08 : 1)" in ios)
        assertTrue(".scaleEffect(configuration.isPressed && !reduceMotion ? 0.9 : 1)" in ios)
        assertTrue("if reduceMotion {\n                        proxy.scrollTo(ShellBar.top, anchor: .top)" in ios)
    }
}
