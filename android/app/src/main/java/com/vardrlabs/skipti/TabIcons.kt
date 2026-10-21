package com.vardrlabs.skipti

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** D-219 (the Look Book's `stroke-width`, L18): the one stroke every Lucide drawing takes, in 24-unit viewport units,
 *  round caps and joins; the twin of iOS's `LucideStroke` (ThemeArtTest reads the Swift file and compares). [WIDTH] is
 *  the icons'; [PLUS] the + glyph's, heavier (the Look Book draws it inline at 2.8). */
object LucideStroke {
    const val WIDTH = 2.4f
    const val PLUS = 2.8f
}

/**
 * The tab bar's icons: LUCIDE, drawn upstream (ISC licence; see NOTICE).
 * The `d` strings below are the UPSTREAM BYTES copied verbatim from
 * github.com/lucide-icons/lucide (icons/{wallet,users,receipt,plus,
 * circle-check,user-round}.svg, D-179), not redrawn, not approximated; the NOTICE
 * entry credits Lucide for their drawing. The builder applies Lucide's
 * own stroke language ([LucideStroke.WIDTH], round caps and joins, no fill) at 24×24, and
 * the nav bar tints them with Tok.accent.
 *
 * No icon dependency is added: these are built with ui-graphics'
 * PathParser over the path strings in [PATHS]: twenty-one drawings since D-249
 * (the tab bar's six first, D-179; the rest added since, each named in NOTICE).
 */
object TabIcons {

    private fun lucide(name: String, vararg d: String, stroke: Float = LucideStroke.WIDTH): ImageVector {
        val builder = ImageVector.Builder(
            name = "lucide_$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (pathData in d) {
            builder.addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = null,
                stroke = SolidColorBlack,
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private val SolidColorBlack = androidx.compose.ui.graphics.SolidColor(Color.Black)

    /**
     * D-179: the upstream path strings, one place, so a JVM test can pin them
     * without drawing (TabIconsTest; iOS's twin is BalanceLineTests
     * .testTheNewGlyphsAreLucidesOwnPaths). Read 2026-09-29 from
     * github.com/lucide-icons/lucide main; SVG circles as their arc paths.
     */
    val PATHS: Map<String, List<String>> = mapOf(
        "wallet" to listOf(
            "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",
            "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4",
        ),
        "users" to listOf(
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M16 3.128a4 4 0 0 1 0 7.744",
            "M22 21v-2a4 4 0 0 0-3-3.87",
            "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8",
        ),
        "circle-check" to listOf(
            "M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20",
            "m16 9-5.5 5.5L8 12",
        ),
        // D-186: read 2026-09-30 from upstream main (icons/user-plus.svg):
        // the path verbatim; its <circle cx=9 cy=7 r=4> as the arc path, as
        // `users` writes it; its two <line>s as the same segments.
        "user-plus" to listOf(
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8",
            "M19 8v6",
            "M22 11h-6",
        ),
        // D-217: read 2026-10-15 from upstream (lucide-static icons/chevron-left.svg and chevron-right.svg): the path
        // data verbatim, the same bytes as iOS's LucideGlyph (a JVM test reads LucideIcon.swift and compares). Both are
        // on Lucide's Feather-derived list: ISC and MIT (NOTICE).
        "chevron-left" to listOf("m15 18-6-6 6-6"),
        "chevron-right" to listOf("m9 18 6-6-6-6"),
        // D-220: read 2026-10-16 from upstream (lucide-static v1.58.0 icons/search.svg and check.svg): the path data
        // verbatim, the same bytes as iOS's LucideGlyph (a JVM test compares); search's <circle cx=11 cy=11 r=8> as its
        // arc path. Both are on Lucide's Feather-derived list: ISC and MIT (NOTICE).
        "search" to listOf("m21 21-4.34-4.34", "M11 3a8 8 0 1 0 0 16 8 8 0 1 0 0-16"),
        "check" to listOf("M20 6 9 17l-5-5"),
        // D-222: receipt, plus and user-round join the table (every drawing is here, compared with iOS's whole
        // LucideGlyph by ThemeArtTest.every_lucide_path_matches_ios).
        "receipt" to listOf(
            "M12 17V7",
            "M16 8h-6a2 2 0 0 0 0 4h4a2 2 0 0 1 0 4H8",
            "M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z",
        ),
        "plus" to listOf("M5 12h14", "M12 5v14"),
        "user-round" to listOf("M12 3a5 5 0 1 0 0 10 5 5 0 1 0 0-10", "M20 21a8 8 0 0 0-16 0"),
        // D-222: read 2026-10-16 from upstream (lucide-static v1.58.0 icons/sun.svg, shield.svg, download.svg and info.svg):
        // the path data verbatim, in upstream's order; each <circle> as its two-arc path from the top (sun cx 12 cy 12 r 4,
        // info cx 12 cy 12 r 10). `download` and `info` are on Lucide's Feather-derived list (ISC and MIT); `sun` and
        // `shield` are not (ISC only). NOTICE names them.
        "sun" to listOf(
            "M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
            "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
        ),
        "shield" to listOf(
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        ),
        "download" to listOf("M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5"),
        "info" to listOf("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20", "M12 16v-4", "M12 8h.01"),
        // D-223: read 2026-10-17 from upstream (lucide-static v1.59.0 icons/inbox.svg), in upstream's order: its
        // <polyline points="22 12 16 12 14 15 10 15 8 12 2 12"> written as M then L through the points in order, then its
        // path data verbatim. The same bytes as iOS's LucideGlyph.inbox (ThemeArtTest compares). NOTICE names it.
        "inbox" to listOf(
            "M22 12 L16 12 L14 15 L10 15 L8 12 L2 12",
            "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
        ),
        // D-224: read 2026-10-17 from upstream (lucide-static v1.59.0 icons/vibrate.svg), in upstream's order: its two
        // paths verbatim, then its <rect width=8 height=14 x=8 y=5 rx=1> as a rounded-rect path (move to (x + rx, y),
        // then line and corner arc clockwise round the four sides, absolute commands spaced as `inbox` writes its polyline).
        // The same bytes as iOS's LucideGlyph.vibrate (ThemeArtTest compares). NOTICE names it.
        "vibrate" to listOf(
            "m2 8 2 2-2 2 2 2-2 2",
            "m22 8-2 2 2 2-2 2 2 2",
            "M9 5 L15 5 A1 1 0 0 1 16 6 L16 18 A1 1 0 0 1 15 19 L9 19 A1 1 0 0 1 8 18 L8 6 A1 1 0 0 1 9 5 Z",
        ),
        // D-225: read 2026-10-17 from upstream (lucide-static v1.59.0 icons/x.svg and lock.svg). `x`: both paths
        // verbatim. `lock`: in upstream's order, its <rect width=18 height=11 x=3 y=11 rx=2 ry=2> as a rounded-rect path
        // (move to (x + rx, y), then line and corner arc clockwise round the four sides, absolute commands, as `vibrate`
        // writes its rect), then its path verbatim. The same bytes as iOS's LucideGlyph (ThemeArtTest compares). Both are
        // on Lucide's Feather-derived list: ISC and MIT (NOTICE).
        "x" to listOf("M18 6 6 18", "m6 6 12 12"),
        "lock" to listOf(
            "M5 11 L19 11 A2 2 0 0 1 21 13 L21 20 A2 2 0 0 1 19 22 L5 22 A2 2 0 0 1 3 20 L3 13 A2 2 0 0 1 5 11 Z",
            "M7 11V7a5 5 0 0 1 10 0v4",
        ),
        // D-231: read 2026-10-19 from upstream (lucide-static v1.59.0 icons/sparkles.svg), in upstream's order: its paths
        // verbatim, then its <circle cx=4 cy=20 r=2> as its two-arc path from the top (as `user-round` writes its circle).
        // The same bytes as iOS's LucideGlyph.sparkles (ThemeArtTest compares). Not on Lucide's Feather-derived list
        // (Feather has no sparkles): ISC only (NOTICE).
        "sparkles" to listOf(
            "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
            "M20 2v4",
            "M22 4h-4",
            "M4 18a2 2 0 1 0 0 4 2 2 0 1 0 0-4",
        ),
        // D-249: read 2026-10-21 from upstream (lucide-static v1.59.0 icons/chart-pie.svg), its two paths verbatim, in
        // upstream's order. The same bytes as iOS's LucideGlyph.chartPie (ThemeArtTest compares). Not on Lucide's
        // Feather-derived list: ISC only (NOTICE).
        "chart-pie" to listOf(
            "M21 12c.552 0 1.005-.449.95-.998a10 10 0 0 0-8.953-8.951c-.55-.055-.998.398-.998.95v8a1 1 0 0 0 1 1z",
            "M21.21 15.89A10 10 0 1 1 8 2.83",
        ),
    )

    /** Balance: lucide `wallet`, verbatim. */
    val Wallet: ImageVector by lazy { lucide("wallet", *PATHS.getValue("wallet").toTypedArray()) }

    /** Groups: lucide `users`, verbatim. */
    val Users: ImageVector by lazy { lucide("users", *PATHS.getValue("users").toTypedArray()) }

    /** D-186: add people to a group, lucide `user-plus`, verbatim. */
    val UserPlus: ImageVector by lazy { lucide("user-plus", *PATHS.getValue("user-plus").toTypedArray()) }

    /** D-217: the back button's chevron (D-217 Task 4's bar): lucide `chevron-left`, verbatim. */
    val ChevronLeft: ImageVector by lazy { lucide("chevron-left", *PATHS.getValue("chevron-left").toTypedArray()) }

    /** D-217: the chevron at the end of a list row: lucide `chevron-right`, verbatim (see [RowChevron]). */
    val ChevronRight: ImageVector by lazy { lucide("chevron-right", *PATHS.getValue("chevron-right").toTypedArray()) }

    /** D-220: the currency list's search field: lucide `search`, verbatim. */
    val Search: ImageVector by lazy { lucide("search", *PATHS.getValue("search").toTypedArray()) }

    /** D-220: the currency list's selected row: lucide `check`, verbatim. */
    val Check: ImageVector by lazy { lucide("check", *PATHS.getValue("check").toTypedArray()) }

    /** D-224 (L50): the done toast's check: lucide `check`, verbatim, at the Look Book's `toastCheckStroke` (3.5). */
    val ToastCheck: ImageVector by lazy { lucide("check", *PATHS.getValue("check").toTypedArray(), stroke = ThemeArt.m("toastCheckStroke")) }

    /** D-222 (L56): You's Appearance tile: lucide `sun`, verbatim. */
    val Sun: ImageVector by lazy { lucide("sun", *PATHS.getValue("sun").toTypedArray()) }

    /** D-222 (L56): You's Security tile: lucide `shield`, verbatim. */
    val Shield: ImageVector by lazy { lucide("shield", *PATHS.getValue("shield").toTypedArray()) }

    /** D-222 (L56): You's Your data tile: lucide `download`, verbatim. */
    val Download: ImageVector by lazy { lucide("download", *PATHS.getValue("download").toTypedArray()) }

    /** D-222 (L56): You's About tile: lucide `info`, verbatim. */
    val Info: ImageVector by lazy { lucide("info", *PATHS.getValue("info").toTypedArray()) }

    /** D-223 (L-D217-10): the empty Expenses list: lucide `inbox`, verbatim. */
    val Inbox: ImageVector by lazy { lucide("inbox", *PATHS.getValue("inbox").toTypedArray()) }

    /** Settle up: lucide `circle-check`, verbatim. */
    val CircleCheck: ImageVector by lazy { lucide("circle-check", *PATHS.getValue("circle-check").toTypedArray()) }

    /** D-224 (L48): the Haptics row's tile: lucide `vibrate`, verbatim. */
    val Vibrate: ImageVector by lazy { lucide("vibrate", *PATHS.getValue("vibrate").toTypedArray()) }

    /** D-225: the clear and remove cross (People, New group): lucide `x`, verbatim. */
    val X: ImageVector by lazy { lucide("x", *PATHS.getValue("x").toTypedArray()) }

    /** D-225: the lock screen's glyph: lucide `lock`, verbatim. */
    val Lock: ImageVector by lazy { lucide("lock", *PATHS.getValue("lock").toTypedArray()) }

    /** D-231 (decision 69): the Less motion row's tile: lucide `sparkles`, verbatim. */
    val Sparkles: ImageVector by lazy { lucide("sparkles", *PATHS.getValue("sparkles").toTypedArray()) }

    /** D-249 (decision 163): the Spending on Home row's tile: lucide `chart-pie`, verbatim. */
    val ChartPie: ImageVector by lazy { lucide("chart-pie", *PATHS.getValue("chart-pie").toTypedArray()) }

    /** Account: lucide `user-round`, verbatim (its circle, cx 12 cy 8 r 5, as the arc path). NOTICE names twenty-one
     *  drawings since D-249 (D-216's set, `sparkles` and `chart-pie`). */
    val UserRound: ImageVector by lazy { lucide("user-round", *PATHS.getValue("user-round").toTypedArray()) }

    /** Expenses: lucide `receipt`, verbatim. */
    val Receipt: ImageVector by lazy { lucide("receipt", *PATHS.getValue("receipt").toTypedArray()) }

    /** Add: lucide `plus`, verbatim, at the + glyph's heavier stroke. */
    val Plus: ImageVector by lazy { lucide("plus", *PATHS.getValue("plus").toTypedArray(), stroke = LucideStroke.PLUS) }
}

/** D-217: the chevron at the end of a list row: Lucide's chevron-right, the same drawing on both phones (it replaces the
 *  text glyph a row used to end in, which drew smaller than iOS's), 20 dp like iOS's `RowChevron`. Decorative: TalkBack skips it. */
@Composable
fun RowChevron(color: Color) {
    Icon(
        imageVector = TabIcons.ChevronRight,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(RowChevronSize.dp),
    )
}

/** The row chevron's size, dp (iOS: `RowChevron.size`, points). */
const val RowChevronSize = 20
