package com.vardrlabs.skipti

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * D-214 (E2 motion, ADR-0041 "Theme art", the founder, 2026-10-15): every animation is a pure function of time and
 * a fixed seed; the renderers only draw what these return. Values from .drafts/d214/motion.py, which ports the
 * Look Book's formulas. This is the twin of iOS ThemeMotion.swift; ThemeMotionTest reads that file.
 */
object MotionClock {
    /** The art never draws faster than this. */
    const val FPS: Double = 30.0

    /** The spec's still mode: no ticking under Reduce Motion or Low Power, or when the art is not on an active screen. */
    fun shouldTick(reduceMotion: Boolean, lowPower: Boolean, active: Boolean): Boolean =
        !reduceMotion && !lowPower && active

    /** One 30 fps interval, and the vsync jitter a frame may arrive early by and still be kept. */
    val INTERVAL_NANOS: Long = (1e9 / FPS).toLong()
    const val SLACK_NANOS: Long = 2_000_000L
}

/** D-231 (decisions 68 to 70): Less motion, the app's own Reduce Motion, the twin of iOS's `MotionSetting`
 *  (ThemeMotionTest reads ThemeMotion.swift's). Stored under [STORAGE_KEY] in the appearance preferences, beside
 *  `haptics` (iOS: `appearance.` + the same word). */
object MotionSetting {
    const val STORAGE_KEY = "lessMotion"

    /** 3 GiB: a phone with less memory starts with Less motion on. */
    const val LOW_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024

    /** The stored value once the person has set it; until then, whether the phone is low on memory. */
    fun parse(stored: Boolean?, lowRam: Boolean): Boolean = stored ?: lowRam

    /** A low-memory phone: the platform's flag, or under 3 GiB. */
    fun lowRam(totalBytes: Long, lowRamFlag: Boolean): Boolean = lowRamFlag || totalBytes < LOW_RAM_BYTES

    /** The one combine: the phone's "remove animations" or the switch. The switch only adds stillness. */
    fun reduced(system: Boolean, lessMotion: Boolean): Boolean = system || lessMotion
}

/** D-231 (decision 69): the Less motion row's words, verbatim on both phones (ThemeMotionTest compares iOS's). */
object MotionCopy {
    const val TITLE = "Less motion"
    const val SUMMARY = "Still art, fewer animations"
}

/** The renderer's frame clock (one per running loop), on ONE grid for the whole window (P2, fix wave 3): time is cut
 *  into 30 fps slots counted from the frame clock's zero, and a gate keeps the first vsync it sees in each new slot (a
 *  vsync up to [MotionClock.SLACK_NANOS] early counts as the next slot). Every art in a window is handed the same vsync
 *  times, so every gate keeps the same vsyncs and the window draws 30 frames a second, not 30 per art on alternating
 *  vsyncs. A gate's first vsync only sets its place (it cannot know whether it is the first of its slot), so a new
 *  art joins on the next slot. A kept vsync must also be [MIN_GAP_NANOS] after the last kept one: after a late frame
 *  that landed late in its slot, the next slot's first vsync is skipped, so a late frame is never followed by a
 *  burst; that rule reads only the shared vsync stream too, so gates that have kept one vsync together stay together.
 *  On any refresh rate the average is at most 30 a second. iOS's twin is `ArtSchedule`. */
class FrameGate {
    private var lastSlot = Long.MIN_VALUE
    private var lastKept = Long.MIN_VALUE

    fun due(nowNanos: Long): Boolean {
        val slot = slot(nowNanos)
        val seen = lastSlot
        if (slot > lastSlot) lastSlot = slot
        if (seen == Long.MIN_VALUE || slot <= seen) return false
        if (lastKept != Long.MIN_VALUE && nowNanos - lastKept < MIN_GAP_NANOS) return false
        lastKept = nowNanos
        return true
    }

    companion object {
        /** The 30 fps slot a vsync falls in, on the window's shared grid. */
        fun slot(nowNanos: Long): Long = Math.floorDiv(nowNanos + MotionClock.SLACK_NANOS, MotionClock.INTERVAL_NANOS)

        /** Three fifths of an interval (20 ms): more than one 60 Hz vsync (16.7 ms, no burst), no more than the
         *  shortest slot-to-slot gap at 48 to 165 Hz (20.8 ms at 48 Hz, 27.8 ms at 144 Hz), so those rates keep
         *  every slot. */
        val MIN_GAP_NANOS: Long = MotionClock.INTERVAL_NANOS * 3 / 5
    }
}

/** The strengths the founder chose (2026-10-15): each multiplies every Look Book opacity of its layer (motion.py 1b). */
object Strength {
    val METRICS: Map<String, Double> = mapOf(
        "auroraDarkPageCurtains" to 0.26, "auroraDarkPageStars" to 0.16, "auroraLightPageCurtains" to 1.0,
        "auroraHeroCurtains" to 1.0, "auroraHeroStars" to 1.0, "norsePageRunes" to 0.79, "norseHero" to 1.0,
        "vardrLightHeroSigns" to 0.60, "vardrDarkHeroSigns" to 0.54,
        // D-226 (.drafts/d226/art.py section 48): Norse's page glows at the Look Book's own strength.
        "norsePageGlow" to 1.0,
        // D-243 (.drafts/d214/motion.py section 6): the hero runes' rest, the page's rest share of its flare (0.142 / 0.19)
        // times the hero's flare (0.20), over the rune peak 0.18.
        "norseHeroRunes" to 0.83,
    )

    fun of(key: String): Float = METRICS.getValue(key).toFloat()
}

/** D-226 (L34, .drafts/d226/art.py section 48): one of Norse's two page glows, the Look Book's radial gradients (LB 220):
 *  [color] at [alpha] at the centre ([centreX], [centreY], fractions of the page) of an ellipse [radiusX] of the page's
 *  width by [radiusY] of its height, clear at [clearAt] of the radius. The twin of iOS's `NorseGlow.Spec`. */
data class NorseGlowSpec(
    val color: Color,
    val alpha: Double,
    val radiusX: Double,
    val radiusY: Double,
    val centreX: Double,
    val centreY: Double,
    val clearAt: Double,
)

/** D-226: Norse's page glows, gold at the top and silver below the bottom; still (no motion), drawn over `Tok.bg` and
 *  under the runes, each alpha times `Strength.of("norsePageGlow")`. ThemeMotionTest reads iOS's `NorseGlow.specs`. */
object NorseGlow {
    val SPECS: List<NorseGlowSpec> = listOf(
        NorseGlowSpec(ArtDraw.RUNE_GOLD, 0.09, 0.90, 0.60, 0.5, 0.0, 0.70),
        NorseGlowSpec(ThemeArt.color("norseSilver"), 0.07, 1.20, 0.80, 0.5, 1.20, 0.60),
    )
}

/** One colour stop of a curtain: RGB 0..255, alpha 0..1, position 0..1 down the curtain. */
data class CurtainStop(val r: Int, val g: Int, val b: Int, val alpha: Double, val at: Double)

data class CurtainSpec(
    val left: Double,
    val height: Double,
    val period: Double,
    val delay: Double,
    val lightOpacity: Double,
    val nightOpacity: Double,
    val stops: List<CurtainStop>,
)

/** x, y: the curtain's top-left in container units after the translate; skew in degrees. */
data class CurtainPose(
    val x: Double, val y: Double, val width: Double, val height: Double,
    val skewDegrees: Double, val sx: Double, val sy: Double,
)

/** Aurora's three curtains, swaying on 13, 17 and 11 s cycles (the Look Book's `sway`). */
object Curtains {
    const val WIDTH = 0.70
    const val TOP = -0.12
    private val PINK_GREEN_CYAN = listOf(
        CurtainStop(214, 92, 170, 0.0, 0.0), CurtainStop(214, 92, 170, 0.42, 0.12),
        CurtainStop(69, 230, 176, 0.85, 0.34), CurtainStop(31, 182, 201, 0.35, 0.64),
        CurtainStop(31, 182, 201, 0.0, 1.0),
    )
    val SPECS: List<CurtainSpec> = listOf(
        CurtainSpec(-0.10, 0.70, 13.0, 0.0, 0.35, 0.80, PINK_GREEN_CYAN),
        CurtainSpec(0.30, 0.58, 17.0, -6.0, 0.35, 0.62, PINK_GREEN_CYAN),
        CurtainSpec(
            0.55, 0.48, 11.0, -3.0, 0.35, 0.50,
            listOf(
                CurtainStop(69, 230, 176, 0.0, 0.0), CurtainStop(69, 230, 176, 0.70, 0.30),
                CurtainStop(31, 182, 201, 0.30, 0.70), CurtainStop(31, 182, 201, 0.0, 1.0),
            ),
        ),
    )

    /** (dx, dy, skew, sx, sy) at 0, 0.5 and 1 of a swing. */
    val KEYS = listOf(
        doubleArrayOf(-0.12, 0.0, -16.0, 1.0, 1.0),
        doubleArrayOf(0.06, 0.02, 6.0, 1.05, 1.15),
        doubleArrayOf(0.14, -0.02, -8.0, 0.95, 1.0),
    )

    /** The still frame's time. */
    const val STILL: Double = 0.0

    /** The streaks, the Look Book's 11 pt pattern: the whole curtain at [STREAK_BASE], then the bars (offset, width
     *  in dp) at [STREAK_BAR] more. A blur wider than the pattern leaves its mean, 0.60 (the Look Book's 2 x 1 +
     *  4 x 0.35 + 1 x 1 + 4 x 0.55 over 11); the floor tests measure the curtains at that mean. The twin of iOS. */
    const val STREAK_PITCH: Double = 11.0
    val STREAK_BARS: List<Pair<Double, Double>> = listOf(0.0 to 2.0, 6.0 to 1.0)
    const val STREAK_BASE = 0.45
    const val STREAK_BAR = 0.55
    val STREAK_MEAN: Double get() = STREAK_BASE + STREAK_BAR * STREAK_BARS.sumOf { it.second } / STREAK_PITCH

    /** The arched hem, the Look Book's `border-radius: 0 0 50% 50% / 0 0 30% 30%`: below [HEM_TOP] of its height a
     *  curtain narrows in two quarter ellipses of radii (half its width, 0.30 of its height). */
    const val HEM_TOP = 0.70

    /** Four thirds of (root 2 - 1): a cubic's control length for a quarter ellipse. */
    const val KAPPA = 0.5522847498

    /** Whether a point of a curtain's own unit square (fx across, fy down) lies inside its arched outline. */
    fun insideHem(fx: Double, fy: Double): Boolean {
        if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return false
        if (fy <= HEM_TOP) return true
        val dx = (fx - 0.5) / 0.5
        val dy = (fy - HEM_TOP) / (1 - HEM_TOP)
        return dx * dx + dy * dy <= 1
    }

    /** The hem's two cubics in a w x h curtain, (c1, c2, end) each: from (w, HEM_TOP h) round to the bottom centre,
     *  then up to (0, HEM_TOP h). The outline is (0, 0), (w, 0), (w, HEM_TOP h), these two, closed. */
    fun hemCurves(w: Double, h: Double): List<DoubleArray> {
        val top = HEM_TOP * h
        val ry = (1 - HEM_TOP) * h
        val rx = w / 2
        return listOf(
            doubleArrayOf(w, top + KAPPA * ry, rx + KAPPA * rx, h, rx, h),
            doubleArrayOf(rx - KAPPA * rx, h, 0.0, top + KAPPA * ry, 0.0, top),
        )
    }

    fun pose(i: Int, t: Double): CurtainPose {
        val s = SPECS[i]
        val u = (t - s.delay) / s.period
        val n = floor(u)
        val f = u - n
        val p = if (n.toLong() % 2L == 0L) f else 1 - f
        val a: DoubleArray
        val b: DoubleArray
        val seg: Double
        if (p < 0.5) { a = KEYS[0]; b = KEYS[1]; seg = p / 0.5 } else { a = KEYS[1]; b = KEYS[2]; seg = (p - 0.5) / 0.5 }
        val e = (1 - cos(PI * seg)) / 2
        val v = DoubleArray(5) { a[it] + (b[it] - a[it]) * e }
        return CurtainPose(
            x = s.left + v[0] * WIDTH, y = TOP + v[1] * s.height, width = WIDTH, height = s.height,
            skewDegrees = v[2], sx = v[3], sy = v[4],
        )
    }
}

data class Star(val x: Double, val y: Double, val radius: Double, val alpha: Double)

/** The night sky's stars (still), the Look Book's eight. */
object Stars {
    val ALL: List<Star> = listOf(
        Star(0.12, 0.18, 1.0, 0.9), Star(0.33, 0.09, 1.0, 0.7), Star(0.61, 0.14, 1.2, 0.9), Star(0.82, 0.26, 1.0, 0.6),
        Star(0.47, 0.31, 1.0, 0.5), Star(0.90, 0.08, 1.0, 0.8), Star(0.24, 0.44, 1.0, 0.4), Star(0.70, 0.40, 1.0, 0.5),
    )
}

data class RunePose(
    val glyph: String, val silver: Boolean, val x: Double, val y: Double, val size: Double,
    val rotationDegrees: Double, val opacity: Double,
)

/** Norse's runes rising behind every page (the Look Book's `runefall`). */
object RuneDrift {
    const val COUNT = 16
    const val PEAK = 0.18

    /** D-243 stage 3 (the founder, 2026-10-20: "Give each rune its own lane"): rune i rises in lane `LANES[i]` of
     *  sixteen, evenly spread from 2% to 94% of the width (lane k's centre at 2 + 92 k / 15 percent; x is the glyph's
     *  centre), so no two runes' ink ever meets and the floors hold on every frame (.drafts/d214/motion.py 1e). D-214's
     *  lanes, (i * 37) % 92 + 2 percent, crowded the sixteen into five clusters. The twin of iOS's `RuneDrift.lanes`
     *  (ThemeMotionTest reads it); glyphs, sizes, colours, speeds and flares are unchanged. */
    val LANES = intArrayOf(1, 8, 14, 5, 11, 2, 7, 15, 6, 10, 0, 9, 13, 4, 12, 3)

    private class Base(val glyph: String, val silver: Boolean, val x: Double, val size: Double)

    private fun base(i: Int, w: Float): Base =
        Base(Runes.FUTHARK[(i * 7) % 24], i % 3 == 0, w.toDouble() * (2 + 92 * LANES[i] / 15.0) / 100, (14 + (i * 5) % 14).toDouble())

    fun opacity(p: Double): Double = when {
        p < 0.12 -> PEAK * p / 0.12
        p < 0.85 -> PEAK + (0.10 - PEAK) * (p - 0.12) / 0.73
        else -> 0.10 * (1 - p) / 0.15
    }

    /** D-243: the rise both rune layers share (the page's and the hero's): at [t], a rune that rises over [duration]
     *  seconds led by [lead], on a ground [h] tall. The twin of iOS's `RuneDrift.rise`. */
    fun rise(glyph: String, silver: Boolean, x: Double, size: Double, t: Double, duration: Double, lead: Double, h: Float): RunePose {
        val u = (t + lead) / duration
        val p = u - floor(u)
        val hh = h.toDouble()
        return RunePose(glyph, silver, x, hh + 30 - p * hh * 680 / 640, size, 14 * p, opacity(p))
    }

    fun pose(i: Int, t: Double, w: Float, h: Float): RunePose {
        val b = base(i, w)
        return rise(b.glyph, b.silver, b.x, b.size, t, (16 + (i * 7) % 14).toDouble(), ((i * 11) % 26).toDouble(), h)
    }

    /** The still frame: the Look Book's reduced-motion layout. */
    fun still(i: Int, w: Float, h: Float): RunePose {
        val b = base(i, w)
        return RunePose(b.glyph, b.silver, b.x, h.toDouble() * (1 - ((i * 41) % 90) / 100.0), b.size, 0.0, 0.10)
    }
}

/** D-243 (U24, the founder's approved Vibrant mock-up, 2026-10-20; .drafts/d214/motion.py sections 5 and 6): Norse's
 *  runes glow as they rise and flare now and then, applied after the rise (RuneDrift's poses do not move). A rune's core
 *  is its rise envelope (the pose's opacity over [RuneDrift.PEAK]) times its rest ([RuneDrift.PEAK] x its layer's
 *  strength) lifted toward its layer's top by the flare; its halo, a radial disc of [HALO_RADIUS] x its size, is [HALO]
 *  at the top of a flare and in proportion below it. The flare: 0 at rest, eased in and out ([EASE], each half its own
 *  segment) up from [RISE] of its cycle to 1 at [TOP], back to 0 at [FALL]. Page rune i flares on a [cycle] s cycle led
 *  by [lead] s; the hero's runes carry their own ([HeroRunes]). Still: the still pose's opacity times the strength, no
 *  flare and no halo. [core] and [halo] allocate nothing (the renderers' calls); [alphas] pairs them. The twin of iOS's
 *  `RuneGlow`; ThemeMotionTest reads its constants. */
object RuneGlow {
    const val PAGE_FLARE = 0.19
    const val HERO_FLARE = 0.20
    const val HALO = 0.05
    const val HALO_RADIUS = 0.8
    const val RISE = 0.62
    const val TOP = 0.76
    const val FALL = 0.90
    val EASE: List<Double> = listOf(0.42, 0.0, 0.58, 1.0)

    fun cycle(i: Int): Double = (7 + (i * 5) % 7).toDouble()
    fun lead(i: Int): Double = ((i * 3) % 11).toDouble()

    /** The flare at [q], a fraction of its cycle in 0 until 1: 0 outside [rise] until [fall], 1 only at [top]. */
    fun shape(q: Double, rise: Double = RISE, top: Double = TOP, fall: Double = FALL): Double = when {
        q < rise || q >= fall -> 0.0
        q < top -> Bezier.y((q - rise) / (top - rise), EASE)
        else -> 1 - Bezier.y((q - top) / (fall - top), EASE)
    }

    /** A flare on a [cycle] s cycle led by [lead] s, at [t]. */
    fun flare(t: Double, cycle: Double, lead: Double): Double {
        val u = (t + lead) / cycle
        return shape(u - floor(u))
    }

    /** Page rune [i]'s flare at [t]. */
    fun flare(i: Int, t: Double): Double = flare(t, cycle(i), lead(i))

    /** A rune's core alpha at its pose's [opacity], its layer's [strength], the layer's flare [top] and the flare [glow]. */
    fun core(opacity: Double, strength: Double, top: Double, glow: Double, still: Boolean): Double {
        val envelope = opacity / RuneDrift.PEAK
        val rest = RuneDrift.PEAK * strength
        return envelope * (rest + (top - rest) * (if (still) 0.0 else glow))
    }

    /** The halo's alpha under a rune of [core] alpha on a layer whose flare tops at [top]; none when still. */
    fun halo(core: Double, top: Double, still: Boolean): Double = if (still) 0.0 else HALO * core / top

    data class Alphas(val core: Double, val halo: Double)

    fun alphas(opacity: Double, strength: Double, top: Double, glow: Double, still: Boolean): Alphas {
        val c = core(opacity, strength, top, glow, still)
        return Alphas(c, halo(c, top, still))
    }
}

/** D-243: one of the Norse hero's own runes ([HeroRunes.SPECS]); the twin of iOS's `HeroRunes.Spec`. */
data class HeroRuneSpec(
    val glyph: String,
    val silver: Boolean,
    val x: Double,
    val size: Double,
    val duration: Double,
    val lead: Double,
    val cycle: Double,
    val flareLead: Double,
    val stillY: Double,
)

/** D-243: the Norse hero's own rising runes: build.py's seven (j = 3..9, the page's formulas) in the mock-up's order,
 *  each in an even lane LEFT of the ᚠ (x is the glyph's centre, a fraction of the hero's width), rising as the page's
 *  runes do ([RuneDrift.rise]) on the hero's height, flaring on their own cycle led by flareLead, clipped to the inner
 *  frame's interior. Still: stillY of the height, upright, at the still opacity 0.10. Drawn like the page's runes at
 *  `Strength.of("norseHeroRunes")` toward [RuneGlow.HERO_FLARE]. ThemeMotionTest reads iOS's `HeroRunes.specs`. */
object HeroRunes {
    val SPECS: List<HeroRuneSpec> = listOf(
        HeroRuneSpec("ᛃ", false, 0.050, 25.0, 23.0, 3.0, 11.0, 4.0, 0.75),
        HeroRuneSpec("ᛜ", true, 0.135, 15.0, 23.0, 7.0, 8.0, 9.0, 0.67),
        HeroRuneSpec("ᚺ", false, 0.220, 26.0, 16.0, 10.0, 12.0, 2.0, 0.42),
        HeroRuneSpec("ᛖ", true, 0.305, 16.0, 16.0, 14.0, 9.0, 7.0, 0.34),
        HeroRuneSpec("ᚱ", false, 0.390, 20.0, 16.0, 18.0, 13.0, 1.0, 0.26),
        HeroRuneSpec("ᛊ", true, 0.475, 17.0, 23.0, 21.0, 10.0, 5.0, 0.91),
        HeroRuneSpec("ᚢ", false, 0.560, 21.0, 23.0, 25.0, 7.0, 10.0, 0.83),
    )

    val COUNT: Int get() = SPECS.size

    fun pose(i: Int, t: Double, w: Float, h: Float): RunePose {
        val s = SPECS[i]
        return RuneDrift.rise(s.glyph, s.silver, w.toDouble() * s.x, s.size, t, s.duration, s.lead, h)
    }

    fun still(i: Int, w: Float, h: Float): RunePose {
        val s = SPECS[i]
        return RunePose(s.glyph, s.silver, w.toDouble() * s.x, h.toDouble() * s.stillY, s.size, 0.0, 0.10)
    }

    fun flare(i: Int, t: Double): Double = RuneGlow.flare(t, SPECS[i].cycle, SPECS[i].flareLead)
}

/** D-243: the Norse hero's ᚠ glows now and then: [REST] (today's 0.16) up to [PEAK] on a [CYCLE] s cycle (rising from
 *  [RISE] of it, top at [TOP], rest again at [FALL], each half [RuneGlow.EASE]), with a radial halo of [HALO_RADIUS] x its
 *  size centred on its box, [HALO] at the top of the glow (motion.py 1c''). Still: [REST], no halo, today's ᚠ. The
 *  renderer multiplies both by `Strength.of("norseHero")`. The twin of iOS's `WatermarkGlow`. */
object WatermarkGlow {
    const val REST = 0.16
    const val PEAK = 0.208
    const val CYCLE = 9.0
    const val RISE = 0.55
    const val TOP = 0.70
    const val FALL = 0.88
    const val HALO = 0.10
    const val HALO_RADIUS = 0.5

    fun glow(t: Double): Double {
        val u = t / CYCLE
        return RuneGlow.shape(u - floor(u), RISE, TOP, FALL)
    }

    fun alpha(t: Double, still: Boolean): Double = if (still) REST else REST + (PEAK - REST) * glow(t)

    fun haloAlpha(t: Double, still: Boolean): Double = if (still) 0.0 else HALO * glow(t)
}

/** D-243: the Futhark ring round You's picture (D-221's RuneRing) turns clockwise, one turn per [PERIOD] seconds of
 *  turning, from upright each time it starts; still it is upright. Each lit rune carries a static radial halo of the ring
 *  colour at [LIT_HALO], radius [LIT_HALO_RADIUS] x the ring's rune size. The twin of iOS's `RingTurn`. */
object RingTurn {
    const val PERIOD = 90.0
    const val STILL = 0.0
    const val LIT_HALO = 0.35
    const val LIT_HALO_RADIUS = 0.5

    /** The ring's turn in degrees, clockwise, in 0 until 360, after [seconds] of turning; not finite is upright. */
    fun degrees(seconds: Double): Double {
        if (!seconds.isFinite()) return 0.0
        val u = seconds / PERIOD
        return 360 * (u - floor(u))
    }
}

data class Floater(
    val glyph: String,
    val bx: Double, val by: Double, val ax: Double, val ay: Double, val sx: Double, val sy: Double, val ph: Double,
    val size: Int,
    val rot: Double,
    val cyan: Boolean,
    val depth: Double,
)

data class SignPose(val x: Double, val y: Double, val rotation: Double, val alpha: Double, val size: Double)

/** Vardr Labs' currency signs drifting across the hero (the Look Book's seeded field). */
object CurrencyField {
    val SIGNS: List<String> = listOf(
        "$", "₹", "€", "£", "¥", "₩", "₽", "฿", "₺", "₦", "₱",
        "₪", "₫", "₴", "元", "R$", "kr", "¢", "₸", "A$", "C$", "zł",
    )

    private class Major(val bx: Double, val by: Double, val size: Int, val cyan: Boolean)

    /** The majors: (bx, by, size, cyan). */
    private val MAJORS = mapOf(
        "₹" to Major(0.80, 0.32, 40, true), "$" to Major(0.62, 0.68, 30, false), "€" to Major(0.90, 0.72, 28, false),
        "£" to Major(0.70, 0.18, 24, false), "¥" to Major(0.94, 0.40, 24, false),
    )

    /** The still frame's time (the Look Book's reduced-motion frame). */
    const val STILL: Double = 4.0

    /** The first [n] draws of Park-Miller from seed 7. */
    fun draws(n: Int): List<Double> {
        var seed = 7L
        return List(n) {
            seed = (seed * 16807L) % 2147483647L
            (seed - 1).toDouble() / 2147483646.0
        }
    }

    val FLOATERS: List<Floater> by lazy {
        val r = draws(SIGNS.size * 10)
        SIGNS.mapIndexed { i, g ->
            val d = r.subList(i * 10, i * 10 + 10)
            val f = Floater(
                glyph = g, bx = 0.06 + d[0] * 0.9, by = 0.1 + d[1] * 0.8, ax = 0.02 + d[2] * 0.05, ay = 0.03 + d[3] * 0.07,
                sx = 0.15 + d[4] * 0.25, sy = 0.12 + d[5] * 0.22, ph = d[6] * 6.283, size = (16 + Math.round(d[7] * 26)).toInt(),
                rot = (d[8] - 0.5) * 0.5, cyan = i % 5 == 0, depth = 0.35 + d[9] * 0.65,
            )
            val m = MAJORS[g]
            if (m != null) {
                Floater(
                    glyph = g, bx = m.bx, by = m.by, ax = 0.025, ay = 0.05, sx = f.sx, sy = f.sy, ph = f.ph, size = m.size,
                    rot = f.rot, cyan = m.cyan || f.cyan, depth = 1.0,
                )
            } else f
        }
    }

    fun pose(f: Floater, t: Double): SignPose {
        val x = f.bx + f.ax * sin(t * f.sx + f.ph)
        val y = f.by + f.ay * cos(t * f.sy + f.ph * 1.3)
        val calm = min(1.0, max(0.28, (x - 0.2) / 0.45))
        return SignPose(
            x = x, y = y, rotation = f.rot + 0.12 * sin(t * 0.3 + f.ph), alpha = (0.16 + 0.5 * f.depth) * calm,
            size = Math.round(f.size * (0.7 + 0.5 * f.depth)).toDouble(),
        )
    }
}

/** The hex bolt turns 30 degrees while pressed (Vardr Labs only), never under Reduce Motion. */
object AddButtonMotion {
    const val TURN_DEGREES = 30f

    /** The turn's own spring (the Look Book's `.45s cubic-bezier(.3,1.4,.5,1)`), the twin of iOS's response 0.45 s and
     *  damping 0.55: stiffness = (2 pi / 0.45) squared for a unit mass. */
    const val TURN_DAMPING = 0.55f
    const val TURN_STIFFNESS = 195f

    fun turn(art: AddButtonArt, pressed: Boolean, reduceMotion: Boolean): Float =
        if (art == AddButtonArt.BOLT && pressed && !reduceMotion) TURN_DEGREES else 0f
}

/** D-221 (L45, motion.py part 5): the settled celebration's burst, the Look Book's `celebrate` as a pure function of
 *  the time since it started. 26 particles leave (0.5, 0.46) of the page; particle i heads along the angle
 *  i / 26 x 6.283 rad (the Look Book's own 6.283; 0 is right, clockwise on screen) over 1.5 + (i % 4) x 0.12 s. The
 *  options' easing maps the run's progress, then the keyframes at 0, [PEAK_AT] and 1 are interpolated linearly in it:
 *  out to the distance while it grows from [START_SCALE] and fades in, then on to [OUTWARD] of it and [DROP] lower
 *  while it shrinks to [END_SCALE] and fades out. Distances are the Look Book's px over its 280 px screen, of the page's
 *  width. The twin of iOS's `SettledBurst`; ThemeMotionTest reads that file. */
object SettledBurst {
    const val COUNT = 26
    const val ORIGIN_X = 0.5
    const val ORIGIN_Y = 0.46
    const val LB_SCREEN = 280.0
    const val BASE_DISTANCE = 70.0
    const val DISTANCE_STEP = 18.0
    const val OUTWARD = 1.25
    const val DROP = 20.0
    const val DURATION = 1.5
    const val DURATION_STEP = 0.12
    const val PEAK_AT = 0.55
    const val START_SCALE = 0.4
    const val END_SCALE = 0.8
    const val ANGLE_STEP = 6.283

    /** CSS `cubic-bezier(.2, .7, .3, 1)`: x1, y1, x2, y2. */
    val EASE: List<Double> = listOf(0.2, 0.7, 0.3, 1.0)

    /** The particles (motion.py: the Look Book's px x 1.3, the type's scale), in points, all in the theme's `ring`:
     *  Aurora a dot with a glow, Norse a rune of the Futhark (`FUTHARK[i % 24]`), Vardr Labs a square. */
    const val AURORA_DOT = 8.0
    const val AURORA_GLOW = 10.0
    const val NORSE_RUNE = 22.0
    const val VARDR_SQUARE = 4.0

    /** Where particle i is, from the origin, in points, for a page [width] wide. */
    data class Pose(val dx: Double, val dy: Double, val scale: Double, val opacity: Double)

    fun duration(i: Int): Double = DURATION + (i % 4) * DURATION_STEP

    /** The CSS cubic-bezier [EASE] at progress [u] (D-224: through [Bezier], its goldens unchanged). */
    fun ease(u: Double): Double = Bezier.y(u, EASE)

    /** Particle [i] [elapsed] seconds in, on a page [width] wide; null once its run is over. */
    fun pose(i: Int, elapsed: Double, width: Double): Pose? {
        val d = duration(i)
        if (elapsed >= d) return null
        val p = ease(maxOf(0.0, elapsed / d))
        val angle = i.toDouble() / COUNT * ANGLE_STEP
        val distance = (BASE_DISTANCE + (i % 5) * DISTANCE_STEP) / LB_SCREEN * width
        val r: Double
        val drop: Double
        val scale: Double
        val opacity: Double
        if (p <= PEAK_AT) {
            val f = p / PEAK_AT
            r = distance * f
            drop = 0.0
            scale = START_SCALE + (1 - START_SCALE) * f
            opacity = f
        } else {
            val f = (p - PEAK_AT) / (1 - PEAK_AT)
            r = distance * (1 + (OUTWARD - 1) * f)
            drop = DROP / LB_SCREEN * width * f
            scale = 1 - (1 - END_SCALE) * f
            opacity = 1 - f
        }
        return Pose(kotlin.math.cos(angle) * r, kotlin.math.sin(angle) * r + drop, scale, opacity)
    }
}

/** D-221 (L45, motion.py part 5): the celebration's timeline: [TOTAL] seconds in all; the page's content dims to
 *  [DIM_TO] over [DIM_IN]; the words fade in over [MESSAGE_IN] rising [MESSAGE_RISE] points, centred at [MESSAGE_TOP]
 *  of the page; both undo the same way at the end. In still mode the dim and the words appear and go without motion,
 *  for the same [TOTAL]. The twin of iOS's `CelebrationTiming`. */
object CelebrationTiming {
    const val TOTAL = 3.6
    const val DIM_TO = 0.22
    const val DIM_IN = 0.3
    const val MESSAGE_IN = 0.4
    const val MESSAGE_RISE = 10.0
    const val MESSAGE_TOP = 0.42
}

/** D-224 (motion.py `bezier`): a CSS `cubic-bezier(x1, y1, x2, y2)` at progress [u]: x(s) = u solved by bisection
 *  (48 halvings), then y(s); 0 at or below 0, 1 at or above 1. [p] is x1, y1, x2, y2. The twin of iOS's `Bezier`. */
object Bezier {
    fun y(u: Double, p: List<Double>): Double {
        if (u <= 0.0) return 0.0
        if (u >= 1.0) return 1.0
        val (x1, y1, x2, y2) = p
        fun coord(s: Double, a: Double, b: Double) = 3 * a * s * (1 - s) * (1 - s) + 3 * b * s * s * (1 - s) + s * s * s
        var lo = 0.0
        var hi = 1.0
        repeat(48) {
            val mid = (lo + hi) / 2
            if (coord(mid, x1, x2) < u) lo = mid else hi = mid
        }
        return coord((lo + hi) / 2, y1, y2)
    }
}

/** D-224 (L49, motion.py part 6): the big done check [pose]d [elapsed] seconds in. The circle's trim over
 *  [CIRCLE_DRAW], eased by [CIRCLE_EASE], times [CIRCLE_DASH] over its length (the Look Book's dash, so it closes a
 *  little early), from 12 o'clock clockwise; the tick's trim from [TICK_AT] over [TICK_DRAW], eased by [TICK_EASE],
 *  times [TICK_DASH] over its length; the pop from [POP_AT] for [POP_DURATION], 1 to [POP_SCALE] to 1, each half eased by
 *  [POP_EASE] (CSS times each keyframe segment; it overshoots); Norse's rune ring one turn per [RUNE_TURN], clockwise.
 *  Still mode draws [STILL_ELAPSED], whose pose is (1, 1, 1, 0). The twin of iOS's `CheckMotion`. */
object CheckMotion {
    const val CIRCLE_DRAW = 0.55
    val CIRCLE_EASE: List<Double> = listOf(0.6, 0.0, 0.2, 1.0)
    const val CIRCLE_DASH = 302.0
    const val TICK_AT = 0.45
    const val TICK_DRAW = 0.35
    val TICK_EASE: List<Double> = listOf(0.3, 0.0, 0.2, 1.0)
    const val TICK_DASH = 60.0
    const val POP_AT = 0.7
    const val POP_DURATION = 0.5
    const val POP_SCALE = 1.08
    val POP_EASE: List<Double> = listOf(0.3, 1.6, 0.5, 1.0)
    const val RUNE_TURN = 14.0
    const val STILL_ELAPSED = 14.0

    /** The circle's length in the check's box (radius `doneCircleRadius`) and the tick's, through [DoneCheck.TICK]. */
    val circleLength: Double get() = 2 * PI * ThemeArt.METRICS.getValue("doneCircleRadius")
    val tickLength: Double
        get() = DoneCheck.TICK.zipWithNext { a, b -> kotlin.math.hypot(b.first - a.first, b.second - a.second) }.sum()

    /** Trims 0 to 1, the scale, and the rune ring's turn in degrees (0 to 360). */
    data class Pose(val circle: Double, val tick: Double, val scale: Double, val degrees: Double)

    private fun seg(elapsed: Double, start: Double, duration: Double, ease: List<Double>): Double =
        Bezier.y(((elapsed - start) / duration).coerceIn(0.0, 1.0), ease)

    fun pose(elapsed: Double): Pose {
        val circle = min(1.0, CIRCLE_DASH * seg(elapsed, 0.0, CIRCLE_DRAW, CIRCLE_EASE) / circleLength)
        val tick = min(1.0, TICK_DASH * seg(elapsed, TICK_AT, TICK_DRAW, TICK_EASE) / tickLength)
        val half = POP_DURATION / 2
        val scale = when {
            elapsed <= POP_AT || elapsed >= POP_AT + POP_DURATION -> 1.0
            elapsed < POP_AT + half -> 1 + (POP_SCALE - 1) * seg(elapsed, POP_AT, half, POP_EASE)
            else -> POP_SCALE + (1 - POP_SCALE) * seg(elapsed, POP_AT + half, half, POP_EASE)
        }
        val turn = 360.0 * elapsed / RUNE_TURN
        val degrees = turn - 360.0 * floor(turn / 360.0)
        return Pose(circle, tick, scale, degrees)
    }
}

/** D-224 (L50, motion.py part 6): the toast [pose]d [elapsed] seconds in: in over [FADE_IN] (opacity 0 to 1, rising
 *  [RISE] points, eased by [EASE]), held, out over the last [FADE_OUT] (opacity, linear), gone at [TOTAL]; still: shown
 *  whole for [TOTAL], then gone. Null when not shown. The twin of iOS's `ToastMotion`. */
object ToastMotion {
    const val FADE_IN = 0.2
    const val FADE_OUT = 0.25
    const val TOTAL = 3.0
    const val RISE = 10.0
    val EASE: List<Double> = listOf(0.2, 0.7, 0.3, 1.0)

    /** Opacity 0 to 1, and how far below its rest place it sits, in points. */
    data class Pose(val opacity: Double, val dy: Double)

    fun pose(elapsed: Double, still: Boolean): Pose? {
        if (elapsed < 0 || elapsed >= TOTAL) return null
        if (still) return Pose(1.0, 0.0)
        if (elapsed < FADE_IN) {
            val p = Bezier.y(elapsed / FADE_IN, EASE)
            return Pose(p, RISE * (1 - p))
        }
        if (elapsed > TOTAL - FADE_OUT) return Pose((TOTAL - elapsed) / FADE_OUT, 0.0)
        return Pose(1.0, 0.0)
    }
}

/** D-224 (Design 3): when a done moment's success haptic plays: a small one at once; a big one when the check lands
 *  (the pop's start, [CheckMotion.POP_AT]), or at once in still mode. The twin of iOS's `DoneHapticTiming`. */
object DoneHapticTiming {
    fun delay(big: Boolean, still: Boolean): Double = if (big && !still) CheckMotion.POP_AT else 0.0
}

/** D-224 (Design 3): a success within [WINDOW] seconds of the previous success is skipped (a settle that also
 *  completes the group would buzz twice). The twin of iOS's `HapticGate`. */
object HapticGate {
    const val WINDOW = 1.0

    fun allows(lastSuccess: Double?, now: Double): Boolean = lastSuccess == null || now - lastSuccess >= WINDOW
}

/** D-224: a moment's time since it started while the art ticks and the clock is finite, else its [still] time (the
 *  check's [CheckMotion.STILL_ELAPSED]). The twin of iOS's `MomentClock`. */
object MomentClock {
    fun elapsed(tick: Boolean, now: Double, startedAt: Double, still: Double): Double {
        val e = now - startedAt
        return if (tick && e.isFinite()) e else still
    }
}

// MARK: - Task 4: the renderer (the twin of iOS's ArtCanvas, PageArt, RuneLayer, NightSky and Dawn)

/** The inputs of still mode. D-231 (decision 71): [reduce] is the phone's "remove animations" OR the app's Less motion,
 *  combined in [of] alone (ThemeMotionTest's one-door guard holds it). */
data class MotionState(val reduce: Boolean, val lowPower: Boolean, val resumed: Boolean) {
    val tick: Boolean get() = MotionClock.shouldTick(reduceMotion = reduce, lowPower = lowPower, active = resumed)

    /** D-239 (decision 137, the founder's): a moment (a done check, the settle celebration and its burst, a switch's
     *  travel, a row opening) is not art: power saving never stills it, only "remove animations" or Less motion does.
     *  The art alone keeps battery saver's still frame. The twin of iOS's `MotionClock.momentTicks`. */
    fun moment(): MotionState = copy(lowPower = false)

    companion object {
        /** The one combine: the switch only adds stillness ([MotionSetting.reduced]). */
        fun of(system: Boolean, lessMotion: Boolean, lowPower: Boolean, resumed: Boolean): MotionState =
            MotionState(MotionSetting.reduced(system, lessMotion), lowPower, resumed)
    }
}

/** D-214: the motion inputs: "remove animations" (animator duration scale 0), battery saver, and whether the screen
 *  is resumed. Read again on every resume and on the power-save broadcast. D-231 (decision 71): the phone's input is
 *  joined here with the app's Less motion ([Appearance.lessMotion], snapshot state, so flipping the switch recomposes
 *  every reader); this is the only place either is read for motion. */
@Composable
fun rememberMotionState(): MotionState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var system by remember { mutableStateOf(animatorsOff(context)) }
    var lowPower by remember { mutableStateOf(powerSave(context)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) { system = animatorsOff(context); lowPower = powerSave(context) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { lowPower = powerSave(context) }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The values may have changed between the first read and the observer: read them once more.
        system = animatorsOff(context); lowPower = powerSave(context)
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
    return MotionState.of(system, Appearance.lessMotion, lowPower, resumed)
}

private fun animatorsOff(c: Context) =
    Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun powerSave(c: Context) = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

object ArtCanvas {
    /** The time the engine is handed: always finite (Curtains.pose truncates to an integer). Paused draws the still
     *  frame; a clock that is not finite falls back to it too, and the still itself falls back to 0. */
    fun seconds(tick: Boolean, now: Double, still: Double): Double {
        val safeStill = if (still.isFinite()) still else 0.0
        return if (tick && now.isFinite()) now else safeStill
    }
}

/** One renderer for all moving art, the twin of iOS's ArtCanvas: a draw modifier fed by the frame clock at 30 fps at
 *  most; when it does not tick it draws [still]. Decorative: no semantics, no input. [state] lets a caller that
 *  needs the inputs itself (the runes) share one read. */
fun Modifier.artCanvas(still: Double, state: MotionState? = null, draw: DrawScope.(t: Double) -> Unit): Modifier = composed {
    val tick = (state ?: rememberMotionState()).tick
    var t by remember { mutableDoubleStateOf(still) }
    LaunchedEffect(tick) {
        if (!tick) return@LaunchedEffect
        val gate = FrameGate()
        while (true) {
            withFrameNanos { now ->
                if (gate.due(now)) t = now / 1e9
            }
        }
    }
    drawBehind { draw(ArtCanvas.seconds(tick, t, still)) }
}

/** D-243: a piece of art that turns (You's rune ring, [RingTurn]), on the art's clock: [degrees] of the seconds it has
 *  been turning, from upright each time it starts ticking (the loop restarts at 0), upright while still (the art's rule:
 *  "remove animations", Less motion, battery saver, or a screen not resumed). The turn is the layer's alone
 *  (rotationZ, read in the layer block): nothing recomposes or re-measures per frame. The twin of iOS's `ArtTurn`. */
fun Modifier.artTurn(state: MotionState? = null, degrees: (Double) -> Double): Modifier = composed {
    val tick = (state ?: rememberMotionState()).tick
    var turning by remember { mutableDoubleStateOf(RingTurn.STILL) }
    LaunchedEffect(tick) {
        turning = RingTurn.STILL
        if (!tick) return@LaunchedEffect
        val gate = FrameGate()
        var start = Double.NaN
        while (true) {
            withFrameNanos { now ->
                if (start.isNaN()) start = now / 1e9
                if (gate.due(now)) turning = now / 1e9 - start
            }
        }
    }
    graphicsLayer { rotationZ = degrees(ArtCanvas.seconds(tick, turning, RingTurn.STILL)).toFloat() }
}

/** The drawing of each art layer, shared by the page grounds and the heroes. */
object ArtDraw {
    /** The rune colours: the Look Book's gold `#CDA750` and silver `#A9AEB6` (its `.runefall span` and `span.s`),
     *  made vibrant by D-242 (U24, the founder's "Vibrant"; .drafts/d214/motion.py GOLD, SILVER). The gold is the
     *  Norse accent. */
    val RUNE_GOLD = Color(0xFFDFB043)
    val RUNE_SILVER = Color(0xFFB8C0CC)

    /** What the curtains draw with that depends only on the canvas size, kept between frames (no per-frame Path,
     *  Brush or Matrix): each curtain's arched outline and gradient, and the streaks' bars. */
    class CurtainCache {
        private var key: Size = Size.Unspecified
        private var density = 0f
        internal val matrix = Matrix()
        internal var hems: List<Path> = emptyList()
        internal var brushes: List<Brush> = emptyList()
        internal var bars: Path = Path()
        /** One paint for the curtains, dithered so the faint tails do not band (the twin of iOS's dithered blur). */
        internal val paint = Paint().apply { asFrameworkPaint().isDither = true }

        fun at(scope: DrawScope): CurtainCache {
            if (scope.size == key && scope.density == density) return this
            key = scope.size
            density = scope.density
            val rw = scope.size.width * Curtains.WIDTH.toFloat()
            hems = Curtains.SPECS.map { spec -> hemPath(rw, scope.size.height * spec.height.toFloat()) }
            brushes = Curtains.SPECS.map { spec ->
                Brush.verticalGradient(
                    colorStops = spec.stops.map { it.at.toFloat() to Color(it.r / 255f, it.g / 255f, it.b / 255f, it.alpha.toFloat()) }.toTypedArray(),
                    startY = 0f, endY = scope.size.height * spec.height.toFloat(),
                )
            }
            val tall = scope.size.height * Curtains.SPECS.maxOf { it.height }.toFloat()
            bars = Path().apply {
                with(scope) {
                    val pitch = Curtains.STREAK_PITCH.toFloat().dp.toPx()
                    var x = 0f
                    while (x < rw) {
                        for ((at, width) in Curtains.STREAK_BARS) {
                            addRect(androidx.compose.ui.geometry.Rect(x + at.toFloat().dp.toPx(), 0f, x + (at + width).toFloat().dp.toPx(), tall))
                        }
                        x += pitch
                    }
                }
            }
            return this
        }
    }

    /** A w x h curtain's arched outline (Curtains.hemCurves), the twin of iOS's `ArtDraw.hem`. */
    fun hemPath(w: Float, h: Float): Path = Path().apply {
        moveTo(0f, 0f)
        lineTo(w, 0f)
        lineTo(w, h * Curtains.HEM_TOP.toFloat())
        for (k in Curtains.hemCurves(w.toDouble(), h.toDouble())) {
            cubicTo(k[0].toFloat(), k[1].toFloat(), k[2].toFloat(), k[3].toFloat(), k[4].toFloat(), k[5].toFloat())
        }
        close()
    }

    /** The three curtains over the caller's sky, drawn into [layer] so the blur (API 31+) reaches the curtains alone and
     *  not the stars or the page: one layer, one blur; then the stars (night) OVER the curtains (D-226, Q7 a, L33: the
     *  Look Book's stars shine through the aurora; before they were drawn under it). Below API 31 there is no render
     *  effect: the curtains are drawn unblurred. The twin of iOS's `ArtDraw.curtains`. */
    fun DrawScope.curtains(
        t: Double, night: Boolean, curtainStrength: Float, starStrength: Float, layer: GraphicsLayer, cache: CurtainCache,
    ) {
        val blur = (if (night) 9 else 14).dp.toPx()
        layer.renderEffect = if (Build.VERSION.SDK_INT >= 31) BlurEffect(blur, blur) else null
        val c = cache.at(this)
        layer.record(this, layoutDirection, IntSize(size.width.roundToInt(), size.height.roundToInt())) {
            curtainBodies(t, night, curtainStrength, c)
        }
        drawLayer(layer)
        if (night && starStrength > 0f) {
            for (s in Stars.ALL) {
                drawCircle(
                    Color.White.copy(alpha = (s.alpha * starStrength).toFloat()), radius = s.radius.dp.toPx(),
                    center = Offset(size.width * s.x.toFloat(), size.height * s.y.toFloat()),
                )
            }
        }
    }

    private fun DrawScope.curtainBodies(t: Double, night: Boolean, strength: Float, cache: CurtainCache) {
        val mode = if (night) BlendMode.Screen else BlendMode.Multiply
        for ((i, spec) in Curtains.SPECS.withIndex()) {
            val p = Curtains.pose(i, t)
            val rw = size.width * p.width.toFloat()
            val rh = size.height * p.height.toFloat()
            val opacity = (if (night) spec.nightOpacity else spec.lightOpacity).toFloat() * strength
            // The curtain's own transform: to its place, then the sway's scale and skew about its centre.
            val matrix = cache.matrix.apply {
                reset()
                this[0, 0] = p.sx.toFloat()
                this[0, 1] = (tan(p.skewDegrees * PI / 180) * p.sx).toFloat()
                this[1, 1] = p.sy.toFloat()
            }
            val hem = cache.hems[i]
            val shading = cache.brushes[i]
            withTransform({
                translate(size.width * p.x.toFloat() + rw / 2, size.height * p.y.toFloat() + rh / 2)
                transform(matrix)
                translate(-rw / 2, -rh / 2)
            }) {
                // The streaks: the arched curtain at the pattern's base, then its bright bars, clipped to the arch too.
                fillHem(hem, shading, Size(rw, rh), opacity * Curtains.STREAK_BASE.toFloat(), mode, cache.paint)
                clipPath(cache.bars) { fillHem(hem, shading, Size(rw, rh), opacity * Curtains.STREAK_BAR.toFloat(), mode, cache.paint) }
            }
        }
    }

    private fun DrawScope.fillHem(hem: Path, shading: Brush, area: Size, alpha: Float, mode: BlendMode, paint: Paint) {
        shading.applyTo(area, paint, alpha)
        paint.blendMode = mode
        drawIntoCanvas { it.drawPath(hem, paint) }
    }

    /** D-243: a rune layer's drawing, laid out once (decision 79: nothing measured or allocated per frame): each rune's
     *  [glyphs] in the rune face at its size (measured in composition, again only when the density or the font scale
     *  changes), and the halos' two unit radial brushes, gold and silver, full at the centre and clear at radius 1 (each
     *  halo scales one to its own radius: the burst dot's technique). The twin of iOS's halo symbols. */
    class RuneGlowCache(internal val glyphs: List<TextLayoutResult>) {
        internal val gold: Brush = Brush.radialGradient(listOf(RUNE_GOLD, RUNE_GOLD.copy(alpha = 0f)), center = Offset.Zero, radius = 1f)
        internal val silver: Brush = Brush.radialGradient(listOf(RUNE_SILVER, RUNE_SILVER.copy(alpha = 0f)), center = Offset.Zero, radius = 1f)

        companion object {
            /** The page's sixteen runes. */
            fun page(measurer: TextMeasurer, density: Density) =
                RuneGlowCache(List(RuneDrift.COUNT) { measureRune(measurer, density, RuneDrift.still(it, 0f, 0f)) })

            /** The Norse hero's seven. */
            fun hero(measurer: TextMeasurer, density: Density) =
                RuneGlowCache(List(HeroRunes.COUNT) { measureRune(measurer, density, HeroRunes.still(it, 0f, 0f)) })
        }
    }

    /** A rune's glyph in the rune face at its size (fixed through the density, as every rune drawn as art). */
    private fun measureRune(measurer: TextMeasurer, density: Density, p: RunePose): TextLayoutResult =
        with(density) { measurer.measure(p.glyph, Type.rune(p.size.toFloat().dp.toSp())) }

    /** Norse's runes, each centred on its pose (the twin of iOS), the still layout when [still]. D-243: at
     *  [RuneGlow.core] over its halo (the flare toward [RuneGlow.PAGE_FLARE]); still: 0.10 x the strength, no halo. */
    fun DrawScope.runes(t: Double, still: Boolean, strength: Float, glow: RuneGlowCache) {
        for (i in 0 until RuneDrift.COUNT) {
            val p = if (still) RuneDrift.still(i, size.width, size.height) else RuneDrift.pose(i, t, size.width, size.height)
            val core = RuneGlow.core(p.opacity, strength.toDouble(), RuneGlow.PAGE_FLARE, if (still) 0.0 else RuneGlow.flare(i, t), still)
            glowingRune(p, glow.glyphs[i], glow, core, RuneGlow.halo(core, RuneGlow.PAGE_FLARE, still))
        }
    }

    /** D-243: the Norse hero's own runes ([HeroRunes]), drawn as the page's are, at `norseHeroRunes` toward
     *  [RuneGlow.HERO_FLARE]. The caller clips them to the inner frame's interior. */
    fun DrawScope.heroRunes(t: Double, still: Boolean, glow: RuneGlowCache) {
        val strength = Strength.of("norseHeroRunes").toDouble()
        for (i in 0 until HeroRunes.COUNT) {
            val p = if (still) HeroRunes.still(i, size.width, size.height) else HeroRunes.pose(i, t, size.width, size.height)
            val core = RuneGlow.core(p.opacity, strength, RuneGlow.HERO_FLARE, if (still) 0.0 else HeroRunes.flare(i, t), still)
            glowingRune(p, glow.glyphs[i], glow, core, RuneGlow.halo(core, RuneGlow.HERO_FLARE, still))
        }
    }

    /** D-243: a rune at its pose: its halo ([RuneGlow.HALO_RADIUS] x its size, centred on it) at [halo], then its glyph
     *  at [core]. */
    private fun DrawScope.glowingRune(p: RunePose, glyph: TextLayoutResult, glow: RuneGlowCache, core: Double, halo: Double) {
        if (core <= 0.0) return
        val x = p.x.toFloat()
        val y = p.y.toFloat()
        if (halo > 0.0) {
            val r = (RuneGlow.HALO_RADIUS * p.size).toFloat().dp.toPx()
            withTransform({
                translate(x, y)
                scale(r, r, Offset.Zero)
            }) { drawCircle(if (p.silver) glow.silver else glow.gold, radius = 1f, center = Offset.Zero, alpha = halo.toFloat()) }
        }
        withTransform({
            translate(x, y)
            rotate(p.rotationDegrees.toFloat(), Offset.Zero)
        }) {
            drawText(
                glyph, color = if (p.silver) RUNE_SILVER else RUNE_GOLD,
                topLeft = Offset(-glyph.size.width / 2f, -glyph.size.height / 2f), alpha = core.toFloat(),
            )
        }
    }

    /** D-243: what the Norse hero draws with, kept between frames (no per-frame Path, Brush or measure): the two-stop
     *  gradient, the notch's clip and the inner frame's interior for its size, density, font scale and direction (filled
     *  by heroArt when [stale]), the ᚠ's halo brush, and the glyphs ([runes], [fehu]) measured in composition. */
    class NorseHeroCache(internal val runes: RuneGlowCache, internal val fehu: TextLayoutResult) {
        private var key: Size = Size.Unspecified
        private var density = 0f
        private var fontScale = 0f
        private var direction: androidx.compose.ui.unit.LayoutDirection? = null
        private var shape: Shape? = null
        internal var gradient: Brush = SolidColor(Color.Transparent)
        internal var fehuHalo: Brush = SolidColor(Color.Transparent)
        internal val notch = Path()
        internal var interior = Rect.Zero

        /** True once when the size, density, font scale, direction or [outline] (by identity: a shape has no equality)
         *  changed; the caller refills the cache. */
        fun stale(scope: DrawScope, outline: Shape): Boolean {
            if (scope.size == key && scope.density == density && scope.fontScale == fontScale &&
                scope.layoutDirection == direction && outline === shape) return false
            shape = outline
            key = scope.size
            density = scope.density
            fontScale = scope.fontScale
            direction = scope.layoutDirection
            return true
        }
    }

    /** D-221 (L45, motion.py part 5): the settled burst [elapsed] seconds in, each particle at its `SettledBurst.pose`
     *  from (ORIGIN_X, ORIGIN_Y) of the canvas, scaled and faded by it, in [color] (the theme's `ring`): Aurora a dot
     *  with its glow, Norse a rune of the Futhark in the rune face ([runes]: the 24 laid out once by [burstRunes], the
     *  twin of iOS's 24 symbols; D-221 fix wave S6, never measured per frame), Vardr Labs a square. Nothing before the
     *  start or once every particle's run is over. The twin of iOS's `ArtDraw.burst`. */
    fun DrawScope.burst(elapsed: Double, theme: AppTheme, color: Color, runes: List<TextLayoutResult>) {
        if (!elapsed.isFinite() || elapsed < 0.0) return
        val width = (size.width / density).toDouble()
        // S6: Aurora's glow, one brush per frame: a unit gradient each dot scales to its own glow.
        val glow = if (theme == AppTheme.AURORA) Brush.radialGradient(
            listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)), center = Offset.Zero, radius = 1f,
        ) else null
        val origin = Offset(size.width * SettledBurst.ORIGIN_X.toFloat(), size.height * SettledBurst.ORIGIN_Y.toFloat())
        for (i in 0 until SettledBurst.COUNT) {
            val p = SettledBurst.pose(i, elapsed, width) ?: continue
            val alpha = p.opacity.toFloat()
            if (alpha <= 0f) continue
            val scale = p.scale.toFloat()
            val at = origin + Offset(p.dx.toFloat().dp.toPx(), p.dy.toFloat().dp.toPx())
            when (theme) {
                AppTheme.AURORA -> burstDot(at, scale, alpha, color, glow!!)
                AppTheme.NORSE -> runes.getOrNull(i % Runes.FUTHARK.size)?.let { burstRune(it, at, scale, alpha, color) }
                AppTheme.VARDR -> burstSquare(at, scale, alpha, color)
            }
        }
    }

    /** Aurora's particle: a dot of AURORA_DOT with a glow AURORA_GLOW beyond it. */
    private fun DrawScope.burstDot(at: Offset, scale: Float, alpha: Float, color: Color, unitGlow: Brush) {
        val dot = (SettledBurst.AURORA_DOT / 2).toFloat().dp.toPx() * scale
        val glow = dot + SettledBurst.AURORA_GLOW.toFloat().dp.toPx() * scale
        withTransform({
            translate(at.x, at.y)
            scale(glow, glow, Offset.Zero)
        }) { drawCircle(unitGlow, radius = 1f, center = Offset.Zero, alpha = alpha) }
        drawCircle(color, radius = dot, center = at, alpha = alpha)
    }

    /** The 24 runes of the Futhark at NORSE_RUNE in the rune face, laid out once for the burst. */
    fun burstRunes(measurer: TextMeasurer, density: Density): List<TextLayoutResult> =
        Runes.FUTHARK.map { measurer.measure(it, Type.rune(with(density) { SettledBurst.NORSE_RUNE.toFloat().dp.toSp() })) }

    /** Norse's particle: a rune at NORSE_RUNE in the rune face, centred on its place. */
    private fun DrawScope.burstRune(glyph: TextLayoutResult, at: Offset, scale: Float, alpha: Float, color: Color) {
        withTransform({ scale(scale, scale, at) }) {
            drawText(glyph, color = color, alpha = alpha, topLeft = Offset(at.x - glyph.size.width / 2f, at.y - glyph.size.height / 2f))
        }
    }

    /** Vardr Labs' particle: a square of VARDR_SQUARE. */
    private fun DrawScope.burstSquare(at: Offset, scale: Float, alpha: Float, color: Color) {
        val side = SettledBurst.VARDR_SQUARE.toFloat().dp.toPx() * scale
        drawRect(color, topLeft = Offset(at.x - side / 2, at.y - side / 2), size = Size(side, side), alpha = alpha)
    }
}

/** Norse's runes: the Look Book's reduced-motion layout when the motion inputs are off. */
object RuneLayer {
    /** Still exactly when Reduce Motion or battery saver is on; when only the screen is not resumed nothing is seen. */
    fun isStill(reduceMotion: Boolean, lowPower: Boolean): Boolean = reduceMotion || lowPower
}

/** P1 (fix wave 3): how far a page's art reaches past its own node, in pixels, the twin of iOS's `.ignoresSafeArea()`
 *  on PageArt. Every screen sits inside the window's safe-drawing padding (D-184, AppRoot), so a page's node stops at
 *  the status bar and the navigation bar; an edge of the node that lies within the inset on its side is carried out
 *  to the window's edge, so the art runs on under the bars (as D-213's glow did, whose blooms were drawn past the
 *  node), and any other edge stays where the node is. */
data class Bleed(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val NONE = Bleed(0f, 0f, 0f, 0f)

        /** [node] in the window's coordinates; the insets are the window's safe-drawing insets in pixels. */
        fun of(node: Rect, window: Size, insetLeft: Float, insetTop: Float, insetRight: Float, insetBottom: Float): Bleed {
            fun reach(gap: Float, inset: Float) = if (gap > 0f && gap <= inset + 0.5f) gap else 0f
            return Bleed(
                reach(node.left, insetLeft), reach(node.top, insetTop),
                reach(window.width - node.right, insetRight), reach(window.height - node.bottom, insetBottom),
            )
        }
    }
}

/** Draws [block] on the node grown by [b]: its size and origin are the bled area's (the art's container). */
private inline fun DrawScope.bled(b: Bleed, block: DrawScope.() -> Unit) = inset(-b.left, -b.top, -b.right, -b.bottom, block)

/** The page ground per theme: Aurora's dawn (light) or night (dark) with the curtains, Norse's rising runes,
 *  Vardr Labs' still dot grid (D-213). The twin of iOS's PageArt: drawn out under the system bars ([Bleed]). */
fun Modifier.pageArt(dark: Boolean): Modifier = composed {
    val bleed = remember { mutableStateOf(Bleed.NONE) }
    val insets = WindowInsets.safeDrawing
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    onGloballyPositioned { c ->
        var root = c
        while (root.parentLayoutCoordinates != null) root = root.parentLayoutCoordinates!!
        val b = Bleed.of(
            Rect(c.positionInRoot(), c.size.toSize()), root.size.toSize(),
            insets.getLeft(density, direction).toFloat(), insets.getTop(density).toFloat(),
            insets.getRight(density, direction).toFloat(), insets.getBottom(density).toFloat(),
        )
        if (b != bleed.value) bleed.value = b
    }.then(
        when (Ground.of(Appearance.theme)) {
            Ground.CURTAINS -> Modifier.curtainsGround(dark, bleed)
            Ground.RUNES -> Modifier.runesGround(dark, bleed)
            Ground.GRID -> Modifier.gridBackground(dark, bleed)
        },
    )
}.graphicsLayer() // The content below gets its own layer: a frame of the art re-records the art alone (F7).

/** Aurora's page: the sky, the curtains and the stars. D-226 (L36): on API 31+ ([TabBarGround.blurs]) the topmost page
 *  art ([artOwner]) records its whole art into the window's art holder ([LocalArtBackdrop]) with its window origin and
 *  re-records the holder's one blur from it (the cards blur the art behind them, as iOS's material does); every other
 *  page art, and every page below API 31, draws as before. */
private fun Modifier.curtainsGround(dark: Boolean, bleed: State<Bleed>): Modifier = composed {
    val layer = rememberGraphicsLayer()
    val cache = remember { ArtDraw.CurtainCache() }
    val curtainStrength = Strength.of(if (dark) "auroraDarkPageCurtains" else "auroraLightPageCurtains")
    val starStrength = if (dark) Strength.of("auroraDarkPageStars") else 0f
    val art = LocalArtBackdrop.current?.takeIf { TabBarGround.blurs(Build.VERSION.SDK_INT) }
    val owns = artOwner(art)
    // Fix wave (M3 a): only WHETHER a card is over the art is read in the draw; a card entering or leaving composition
    // (a list item scrolling) no longer re-records the art and its blur.
    val cards = remember(art) { derivedStateOf { art != null && art.panes.isNotEmpty() } }
    val window = remember { floatArrayOf(0f, 0f) }
    onGloballyPositioned { c -> c.positionInWindow().let { window[0] = it.x; window[1] = it.y } }
        .artCanvas(Curtains.STILL) { t ->
            val b = bleed.value
            bled(b) {
                val content = art?.content
                if (art != null && content != null && owns()) {
                    // The bled area's origin in the window: the node's, less the bleed.
                    art.sourceOffset = Offset(window[0] - b.left, window[1] - b.top)
                    content.record(this, layoutDirection, IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                        auroraArt(t, dark, curtainStrength, starStrength, layer, cache)
                    }
                    if (!art.recorded) art.recorded = true
                    // The one blur every glass card draws (only while a card is over this art).
                    if (cards.value) art.recordArtBlur(this)
                    drawLayer(content)
                } else {
                    auroraArt(t, dark, curtainStrength, starStrength, layer, cache)
                }
            }
        }
}

/** Aurora's whole page art at [t]: the night sky or the dawn, then the curtains and the stars. */
private fun DrawScope.auroraArt(
    t: Double, dark: Boolean, curtainStrength: Float, starStrength: Float, layer: GraphicsLayer, cache: ArtDraw.CurtainCache,
) {
    if (dark) nightSkyBody() else dawnBody()
    with(ArtDraw) { curtains(t, dark, curtainStrength, starStrength, layer, cache) }
}

private fun Modifier.runesGround(dark: Boolean, bleed: State<Bleed>): Modifier = composed {
    val motion = rememberMotionState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // D-243: the sixteen glyphs and the halos' brushes, once per density and font scale (never per frame).
    val halos = remember(measurer, density) { ArtDraw.RuneGlowCache.page(measurer, density) }
    val still = RuneLayer.isStill(motion.reduce, motion.lowPower)
    val strength = Strength.of("norsePageRunes")
    val bg = Tok.bg(dark)
    val glow = Strength.of("norsePageGlow")
    drawBehind {
        bled(bleed.value) {
            drawRect(bg)
            // D-226 (L34, .drafts/d226/art.py section 48): the Look Book's two radial glows, still, under the runes, by the
            // dawn's technique: the canvas scaled to the ellipse's radii, a UNIT radial gradient clear at its stop.
            for (g in NorseGlow.SPECS) {
                val c = g.color.copy(alpha = (g.alpha * glow).toFloat())
                withTransform({
                    translate(size.width * g.centreX.toFloat(), size.height * g.centreY.toFloat())
                    scale(size.width * g.radiusX.toFloat(), size.height * g.radiusY.toFloat(), Offset.Zero)
                }) {
                    drawRect(
                        Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset.Zero, radius = g.clearAt.toFloat()),
                        topLeft = Offset(-2f, -2f), size = Size(4f, 4f),
                    )
                }
            }
        }
    }.artCanvas(0.0, motion) { t ->
        bled(bleed.value) { with(ArtDraw) { runes(t, still, strength, halos) } }
    }
}

/** The hero's night sky; D-222: shared with Appearance's Aurora swatch. */
internal fun DrawScope.nightSkyBody() {
    drawRect(
        Brush.verticalGradient(0f to Color(0xFF03070D), 0.55f to Color(0xFF071321), 1f to Color(0xFF0A1B26), startY = 0f, endY = size.height),
    )
}

/** Aurora light's arctic dawn: a pale vertical wash with a glow rising from above the top edge. The glow is the Look
 *  Book's `radial-gradient(ellipse 120% 60% at 50% -10%, #D2F0E5, transparent 70%)`, drawn by scaling the canvas to
 *  the ellipse's radii (1.2 x width, 0.6 x height) and filling a UNIT radial gradient that reaches clear at 0.70 of
 *  the radius, so the falloff is elliptical, as on iOS. */
private fun DrawScope.dawnBody() {
    drawRect(
        Brush.verticalGradient(0f to Color(0xFFE1F1EC), 0.55f to Color(0xFFEEF5F3), 1f to Color(0xFFEEF5F3), startY = 0f, endY = size.height),
    )
    val glow = Color(0xFFD2F0E5)
    withTransform({
        translate(size.width * 0.5f, -size.height * 0.10f)
        scale(size.width * 1.20f, size.height * 0.60f, Offset.Zero)
    }) {
        drawRect(
            Brush.radialGradient(listOf(glow, glow.copy(alpha = 0f)), center = Offset.Zero, radius = 0.70f),
            topLeft = Offset(-2f, -2f), size = Size(4f, 4f),
        )
    }
}

// MARK: - Task 6: the heroes (the twin of iOS's HeroArt)

/** D-214: a hero's art per theme, drawn behind its content: Aurora: the hero's night sky with stars and curtains
 *  (always night, at the founder's full strength, 2026-10-15); Norse: the two-stop gradient at 160 degrees (D-226), the
 *  ᚠ watermark and the inner frame (still); Vardr Labs: the flat ground, the frame marks and the drifting currency signs. The ART is clipped to
 *  [shape], the hero's content is not (F10, the twin of iOS's `.background { HeroArt().clipShape(shape) }`).
 *  Decoration: no semantics, no input (draw modifiers only). The content gets its own layer after the art (F7). */
fun Modifier.heroArt(dark: Boolean, shape: Shape): Modifier = composed {
    val base = Modifier.background(heroBrush(dark), shape)
    when (Appearance.theme) {
        AppTheme.AURORA -> {
            val layer = rememberGraphicsLayer()
            val cache = remember { ArtDraw.CurtainCache() }
            val curtainK = Strength.of("auroraHeroCurtains")
            val starK = Strength.of("auroraHeroStars")
            base.artCanvas(Curtains.STILL) { t ->
                clippedTo(shape) { with(ArtDraw) { curtains(t, true, curtainK, starK, layer, cache) } }
            }
        }
        AppTheme.NORSE -> {
            // D-243: the hero's own rising runes and the glowing ᚠ move on the art's clock, in their own layer between the
            // still gradient and the still frame; the art's one rule of stillness (power saving included).
            val motion = rememberMotionState()
            val still = RuneLayer.isStill(motion.reduce, motion.lowPower)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val accent = Tok.accent(dark)
            val k = Strength.of("norseHero")
            val start = Tok.heroStart(dark)
            val end = Tok.heroEnd(dark)
            // Keyed on the colours the cache's brushes are filled from (fix wave M6), not on `dark` alone.
            val cache = remember(measurer, density, start, end, accent) {
                // ᚠ at heroWatermark dp (fixed through the density), measured once.
                val glyph = with(density) { measurer.measure(Runes.BALANCE, Type.rune(ThemeArt.m("heroWatermark").dp.toSp())) }
                ArtDraw.NorseHeroCache(ArtDraw.RuneGlowCache.hero(measurer, density), glyph)
            }
            // The gradient, the notch and the interior for this size (again only when the size, density, font scale,
            // direction or shape changes).
            fun DrawScope.fill() {
                if (!cache.stale(this, shape)) return
                // D-226 (L31, art.py section 49): the Look Book's two stops at CSS 160 degrees for this size.
                val (from, to) = CssGradient.unitPoints(160.0, size.width.toDouble(), size.height.toDouble())
                cache.gradient = Brush.linearGradient(
                    listOf(start, end),
                    start = Offset(from.x * size.width, from.y * size.height),
                    end = Offset(to.x * size.width, to.y * size.height),
                )
                cache.notch.reset()
                cache.notch.addOutline(shape.createOutline(size, layoutDirection, this))
                // The inner frame's interior: inset heroFrameInset and its 1 dp line.
                val inner = (ThemeArt.m("heroFrameInset") + 1).dp.toPx()
                cache.interior = Rect(inner, inner, size.width - inner, size.height - inner)
                cache.fehuHalo = Brush.radialGradient(listOf(accent, accent.copy(alpha = 0f)), center = Offset.Zero, radius = 1f)
            }
            Modifier
                // D-243 (T7): Norse's edge glow, outside the clipped art and beneath it (ThemeArt.kt's norseEdgeGlow).
                .norseEdgeGlow(accent, ThemeArt.m("chamfer"))
                .drawBehind {
                    fill()
                    clipPath(cache.notch) { drawRect(cache.gradient) }
                }
                .graphicsLayer()
                .artCanvas(0.0, motion) { t ->
                    fill()
                    clipRect(cache.interior.left, cache.interior.top, cache.interior.right, cache.interior.bottom) {
                        with(ArtDraw) { heroRunes(t, still, cache.runes) }
                    }
                    clipPath(cache.notch) {
                        // ᚠ's right edge heroWatermarkTrailing in, its top at heroWatermarkTop (above the top: D-226 scaled
                        // 92, 10, -6 by 1.28), at WatermarkGlow's alpha over its halo while it glows (still: 0.16, no halo).
                        val glyph = cache.fehu
                        val left = size.width - ThemeArt.m("heroWatermarkTrailing").dp.toPx() - glyph.size.width
                        val top = ThemeArt.m("heroWatermarkTop").dp.toPx()
                        val halo = (WatermarkGlow.haloAlpha(t, still) * k).toFloat()
                        if (halo > 0f) {
                            val r = (WatermarkGlow.HALO_RADIUS * ThemeArt.m("heroWatermark")).toFloat().dp.toPx()
                            withTransform({
                                translate(left + glyph.size.width / 2f, top + glyph.size.height / 2f)
                                scale(r, r, Offset.Zero)
                            }) { drawCircle(cache.fehuHalo, radius = 1f, center = Offset.Zero, alpha = halo) }
                        }
                        drawText(glyph, color = accent, alpha = (WatermarkGlow.alpha(t, still) * k).toFloat(), topLeft = Offset(left, top))
                    }
                }
                .graphicsLayer()
                .drawBehind {
                    clipPath(cache.notch) {
                        // The inner frame: inset heroFrameInset, 1 dp, accent at 0.25.
                        val inset = ThemeArt.m("heroFrameInset").dp.toPx()
                        val line = 1.dp.toPx()
                        drawRect(
                            accent.copy(alpha = 0.25f * k), topLeft = Offset(inset + line / 2, inset + line / 2),
                            size = Size(size.width - 2 * inset - line, size.height - 2 * inset - line), style = Stroke(line),
                        )
                    }
                }
                // D-226 (WORKLOG decision 29): the hero's edge on the straight sides only, as every Norse card's.
                .notchEdge(ThemeArt.color("norseEdge"), ThemeArt.m("chamfer"))
        }
        AppTheme.VARDR -> {
            val measurer = rememberTextMeasurer(cacheSize = 64)
            val steel = Tok.accent(dark)
            val cyan = Tok.ring(dark)
            val mark = Tok.textDim(dark).copy(alpha = ThemeArt.m("frameAlpha"))
            val k = Strength.of(if (dark) "vardrDarkHeroSigns" else "vardrLightHeroSigns")
            val line1 = Tok.accentSoft(dark)
            base
                .drawBehind {
                    val px = 1.dp.toPx()
                    ArtGeometry.frameMarks(size.width, size.height, ThemeArt.m("frameInset").dp.toPx(), ThemeArt.m("frameLength").dp.toPx())
                        .forEach { (a, b) -> drawLine(mark, a, b, strokeWidth = px) }
                }
                .artCanvas(CurrencyField.STILL) { t ->
                    clippedTo(shape) {
                        val widthDp = size.width / density
                        for (f in CurrencyField.FLOATERS) {
                            val p = CurrencyField.pose(f, t)
                            val style = Type.mono(12, if (f.cyan) cyan else steel, FontWeight.Medium)
                                .copy(fontSize = (p.size * widthDp / 560).toFloat().dp.toSp())
                            val glyph = measurer.measure(f.glyph, style)
                            withTransform({
                                translate(size.width * p.x.toFloat(), size.height * p.y.toFloat())
                                rotate(Math.toDegrees(p.rotation).toFloat(), Offset.Zero)
                            }) {
                                drawText(
                                    glyph, color = if (f.cyan) cyan else steel, alpha = (p.alpha * k).toFloat(),
                                    topLeft = Offset(-glyph.size.width / 2f, -glyph.size.height / 2f),
                                )
                            }
                        }
                    }
                }
                .drawBehind {
                    // Light only: the inner 1 dp line, one dp in from the edge.
                    if (!dark) {
                        val line = 1.dp.toPx()
                        drawRect(
                            line1, topLeft = Offset(line * 1.5f, line * 1.5f),
                            size = Size(size.width - 3 * line, size.height - 3 * line), style = Stroke(line),
                        )
                    }
                }
                .border(1.dp, Tok.hairline(dark), shape)
        }
    }.graphicsLayer()
}

/** Draws [block] clipped to [shape]'s outline at this size: the art alone is clipped, never the hero's content. */
private inline fun DrawScope.clippedTo(shape: Shape, block: DrawScope.() -> Unit) {
    val outline = shape.createOutline(size, layoutDirection, this)
    clipPath(Path().apply { addOutline(outline) }, block = block)
}
