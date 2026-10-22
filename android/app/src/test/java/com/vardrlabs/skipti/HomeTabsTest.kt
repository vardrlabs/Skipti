package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-179: the bar (the founder's canvas, 2026-09-26), the twin of iOS's
 * BalanceLineTests.testHomeShellHasTheFourTabsAndTheAddButtonInAndroidsOrder
 * and testTheNewGlyphsAreLucidesOwnPaths, and of testAGroupsToneComesFromItsId.
 */
class HomeTabsTest {

    @Test
    fun the_bar_is_four_tabs_with_the_add_button_between_expenses_and_groups() {
        // D-180: Expenses and Groups swapped (the founder, 2026-09-29).
        assertEquals(listOf("Balance", "Expenses", "Groups", "Settle up"), HomeTab.entries.map { it.label })
        assertEquals(
            listOf("Balance", "Expenses", null, "Groups", "Settle up"),
            HomeTab.bar.map { it?.label },
        )
        assertEquals("Add an expense", HomeTab.ADD_LABEL)
    }

    /** Lucide's upstream bytes, written here independently of TabIcons. */
    @Test
    fun the_new_glyphs_are_lucides_own_paths() {
        assertEquals(
            mapOf(
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
                // D-186: user-plus, from upstream's SVG read 2026-09-30.
                "user-plus" to listOf(
                    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
                    "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8",
                    "M19 8v6",
                    "M22 11h-6",
                ),
                // D-217: chevron-left and chevron-right, from upstream's SVGs read 2026-10-15.
                "chevron-left" to listOf("m15 18-6-6 6-6"),
                "chevron-right" to listOf("m9 18 6-6-6-6"),
                // D-220: search and check, from upstream's SVGs (lucide-static v1.58.0) read 2026-10-16; search's circle
                // (cx 11, cy 11, r 8) as its arc path.
                "search" to listOf("m21 21-4.34-4.34", "M11 3a8 8 0 1 0 0 16 8 8 0 1 0 0-16"),
                "check" to listOf("M20 6 9 17l-5-5"),
                // D-222: receipt, plus and user-round join the table (every drawing is in it now, compared with iOS's),
                // with sun, shield, download and info from upstream's SVGs (lucide-static v1.58.0) read 2026-10-16;
                // the sun's circle (cx 12, cy 12, r 4) and info's (r 10) as their arc paths from the top.
                "receipt" to listOf(
                    "M12 17V7",
                    "M16 8h-6a2 2 0 0 0 0 4h4a2 2 0 0 1 0 4H8",
                    "M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z",
                ),
                "plus" to listOf("M5 12h14", "M12 5v14"),
                "user-round" to listOf("M12 3a5 5 0 1 0 0 10 5 5 0 1 0 0-10", "M20 21a8 8 0 0 0-16 0"),
                "sun" to listOf(
                    "M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
                    "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
                ),
                "shield" to listOf(
                    "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
                ),
                "download" to listOf("M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5"),
                "info" to listOf("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20", "M12 16v-4", "M12 8h.01"),
                // D-223: inbox, from upstream's SVG (lucide-static v1.59.0) read 2026-10-17; its polyline
                // (22 12, 16 12, 14 15, 10 15, 8 12, 2 12) as M then L through the points in order, then its path.
                "inbox" to listOf(
                    "M22 12 L16 12 L14 15 L10 15 L8 12 L2 12",
                    "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
                ),
                // D-224: vibrate, from upstream's SVG (lucide-static v1.59.0) read 2026-10-17: its two paths verbatim,
                // then its <rect width=8 height=14 x=8 y=5 rx=1> as M to (x + rx, y), then
                // line and corner arc clockwise round the four sides, absolute commands spaced as inbox's polyline.
                "vibrate" to listOf(
                    "m2 8 2 2-2 2 2 2-2 2",
                    "m22 8-2 2 2 2-2 2 2 2",
                    "M9 5 L15 5 A1 1 0 0 1 16 6 L16 18 A1 1 0 0 1 15 19 L9 19 A1 1 0 0 1 8 18 L8 6 A1 1 0 0 1 9 5 Z",
                ),
                // D-225: x and lock, from upstream's SVGs (lucide-static v1.59.0) read 2026-10-17: x's two paths verbatim;
                // lock's <rect width=18 height=11 x=3 y=11 rx=2 ry=2> as M to (x + rx, y), then line and corner arc
                // clockwise round the four sides, as vibrate's rect, then its path verbatim.
                "x" to listOf("M18 6 6 18", "m6 6 12 12"),
                "lock" to listOf(
                    "M5 11 L19 11 A2 2 0 0 1 21 13 L21 20 A2 2 0 0 1 19 22 L5 22 A2 2 0 0 1 3 20 L3 13 A2 2 0 0 1 5 11 Z",
                    "M7 11V7a5 5 0 0 1 10 0v4",
                ),
                // D-231: sparkles, from upstream's SVG (lucide-static v1.59.0) read 2026-10-19: its three paths verbatim,
                // then its <circle cx=4 cy=20 r=2> as its two-arc path from the top (as search's and info's circles).
                "sparkles" to listOf(
                    "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
                    "M20 2v4",
                    "M22 4h-4",
                    "M4 18a2 2 0 1 0 0 4 2 2 0 1 0 0-4",
                ),
                // D-249: chart-pie, from upstream's SVG (lucide-static v1.59.0) read 2026-10-21: its two paths verbatim.
                "chart-pie" to listOf(
                    "M21 12c.552 0 1.005-.449.95-.998a10 10 0 0 0-8.953-8.951c-.55-.055-.998.398-.998.95v8a1 1 0 0 0 1 1z",
                    "M21.21 15.89A10 10 0 1 1 8 2.83",
                ),
            ),
            TabIcons.PATHS,
        )
    }

    /** Worked by hand: 0x00 -> coral, 0x05 -> pink, 0x06 -> coral,
     *  0xa1 = 161 -> 5 pink, 0xff = 255 -> 3 amber. */
    @Test
    fun a_groups_tone_comes_from_its_id() {
        val tail = "0".repeat(30)
        assertEquals(GroupTone.CORAL, GroupTone.of("00$tail"))
        assertEquals(GroupTone.PINK, GroupTone.of("05$tail"))
        assertEquals(GroupTone.CORAL, GroupTone.of("06$tail"))
        assertEquals(GroupTone.PINK, GroupTone.of("a1$tail"))
        assertEquals(GroupTone.AMBER, GroupTone.of("ff$tail"))
        assertEquals("D-220 (L29): blue is violet's place, the second of the six", GroupTone.BLUE, GroupTone.of("01$tail"))
        assertEquals("G", GroupTone.initial("  goa trip"))
        assertEquals("?", GroupTone.initial("   "))
        assertEquals("1 person · INR", GroupsCopy.detail(1, "INR"))
    }

    /** D-205: the twin of iOS's testATapOnTheShownTabGoesToItsRootThenItsTop. */
    @Test
    fun a_tap_on_the_shown_tab_goes_to_its_root_then_its_top() {
        val g = HomeTab.GROUPS
        assertEquals(TabTap.PopToRoot(g), TabTap.decide(g, g, setOf(g)))
        assertEquals(TabTap.ScrollToTop(g), TabTap.decide(g, g, emptySet()))
        assertEquals(TabTap.ScrollToTop(g), TabTap.decide(g, g, setOf(HomeTab.BALANCE)))
        // Another tab only switches, whatever is covered.
        assertEquals(TabTap.SwitchTo(HomeTab.BALANCE), TabTap.decide(g, HomeTab.BALANCE, setOf(HomeTab.BALANCE, g)))
        for (tab in HomeTab.entries.filter { it != HomeTab.SETTLE }) {
            assertEquals(TabTap.SwitchTo(tab), TabTap.decide(HomeTab.SETTLE, tab, HomeTab.entries.toSet()))
        }
    }

    private fun main(file: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$file").readText()

    /** D-220 (L25): the detail lines drawn in the body draw their parts with `Separator.annotated` (Norse's gold
     *  rune); the white lines over a photo keep the joined string (a white rune). The parts join to the string. */
    @Test
    fun group_detail_lines_use_the_separator() {
        try {
            for (theme in AppTheme.entries) {
                Appearance.setForTest(theme = theme)
                assertEquals(listOf("4 people", "INR"), GroupsCopy.detailParts(4, "INR"))
                assertEquals(GroupsCopy.detailParts(4, "INR").joinToString(Separator.meta()), GroupsCopy.detail(4, "INR"))
                assertEquals(listOf("4 people", "settles in INR"), OverviewCopy.settlesInParts(4, "INR"))
                assertEquals(OverviewCopy.settlesInParts(4, "INR").joinToString(Separator.meta()), OverviewCopy.settlesIn(4, "INR"))
            }
        } finally {
            Appearance.setForTest()
        }
        val groups = main("GroupsTab.kt")
        assertEquals("the plain card", 1, groups.split("Separator.annotated(GroupsCopy.detailParts(named.members.size, named.group.settlementCurrency))").size - 1)
        assertEquals("the photo card", 1, groups.split("GroupsCopy.detail(named.members.size, named.group.settlementCurrency).withRunes()").size - 1)
        val overview = main("OverviewTab.kt")
        assertEquals("the tile row", 1, overview.split("Separator.annotated(OverviewCopy.settlesInParts(n.members.size, n.group.settlementCurrency))").size - 1)
        assertEquals("the photo band", 1, overview.split("OverviewCopy.settlesIn(n.members.size, n.group.settlementCurrency).withRunes()").size - 1)
    }

    /** D-220 (L28): "People in X" on a plain Groups card is plain words that take a tap, as on iOS: no TextButton. */
    @Test
    fun the_people_link_is_plain_text() {
        val groups = main("GroupsTab.kt")
        assertFalse("no TextButton", "TextButton" in groups)
        assertTrue("the words take the tap", ".clickable(role = Role.Button) { onPeople(named.group.id) }" in groups)
        // D-220 fix wave (I1): both card kinds' links take a button role, never a bare clickable. D-223 fix wave (I1,
        // decision 20): laid out 44 dp tall, iOS's 44 pt (the plain card is iOS's 136); Compose widens the touch area
        // to 48 dp where no neighbour claims it.
        assertEquals("the layout height", 44, PeopleLinkMinHeight)
        val target = Regex("""\.heightIn\(min = PeopleLinkMinHeight\.dp\)\s*\.clickable\(role = Role\.Button\) \{ onPeople\(named\.group\.id\) \}""")
        assertEquals("both cards' links", 2, target.findAll(groups).count())
        assertFalse("no link without a role", ".clickable { onPeople(" in groups)
        // Minor 9: the currency chip on Add expense is a button too. D-225 T7: it opens the currency list page.
        assertTrue("the currency chip's role", ".clickable(role = Role.Button) { listingCurrencies = true }" in main("AddExpenseScreen.kt"))
    }

    /** D-223 follow-up (R1, decision 22): the photo card's inner gaps are iOS's `photoCard`'s (iOS is the scale: 190.67
     *  pt with the link, Android was 176.76): its label stack's spacing on both sides of the spacer and between the title
     *  and the detail, the spacer's least height, and the card stack's spacing above "People in X". Read off the Swift. */
    @Test
    fun the_photo_card_takes_ioss_gaps() {
        val ios = java.io.File("../../ios/Skipti/App/GroupsScreen.swift").readText()
            .substringAfter("private func photoCard(").substringBefore("private func plainCard(")
        val stacks = Regex("""VStack\(alignment: \.leading, spacing: (\d+)\)""").findAll(ios).map { it.groupValues[1].toInt() }.toList()
        assertEquals("iOS's card stack, then its label stack", listOf(PhotoCardGaps.LINK, PhotoCardGaps.STACK), stacks)
        val spacer = Regex("""\.frame\(height: PhotoCard\.band - 16, alignment: \.top\)\s*Spacer\(minLength: (\d+)\)""").find(ios)
        assertEquals("iOS's spacer under the band", PhotoCardGaps.TITLE_MIN, spacer?.groupValues?.get(1)?.toInt())
        // The values, pinned, so a change on both sides at once still fails here.
        assertEquals(listOf(2, 12, 10), listOf(PhotoCardGaps.STACK, PhotoCardGaps.TITLE_MIN, PhotoCardGaps.LINK))
        assertEquals("band to title", 16, PhotoCardGaps.ABOVE_TITLE)
        val card = main("GroupsTab.kt").substringAfter("private fun PhotoGroupCard(").substringBefore("\n}\n")
        assertTrue("the band to the title", "Column(Modifier.padding(top = PhotoCardGaps.ABOVE_TITLE.dp)) {" in card)
        assertTrue("the title to the detail", "Column(verticalArrangement = Arrangement.spacedBy(PhotoCardGaps.STACK.dp)) {" in card)
        assertTrue("above the link", ".padding(top = PhotoCardGaps.LINK.dp)" in card)
        assertFalse("no gap of its own", Regex("""padding\(top = \d+\.dp\)""").containsMatchIn(card))
    }

    /** D-220 (L-D217-3): the tab bar's glass blurs the shell's page: the shell provides one backdrop, the tab root on
     *  screen records into it, and the page runs under the bar (the bar overlays it, as iOS's does) keeping the bar's
     *  height clear at its end. */
    @Test
    fun the_tab_bar_glass_is_the_shells_backdrop() {
        val bar = main("TabBar.kt")
        assertTrue("the bar reads the shell's backdrop", "LocalShellBackdrop.current" in bar)
        assertTrue("through the film decision", "TabBarGround.film(" in bar)
        assertTrue("as glass", "backdropGlass(" in bar)
        val shell = main("AppRoot.kt").substringAfter("internal fun HomeShell(")
        assertTrue("the shell provides it", "LocalShellBackdrop provides" in shell)
        assertTrue("and the bar's height", "LocalShellBarInset provides" in shell)
        val scaffold = main("SkiptiBar.kt").substringAfter("fun TabRootScaffold(").substringBefore("\n}\n")
        assertTrue("the root records into the shell's", "LocalShellBackdrop.current" in scaffold)
        assertTrue("and keeps the bar's height clear", "LocalShellBarInset.current" in scaffold)
        val group = main("OverviewTab.kt").substringAfter("fun GroupPage(")
        assertTrue("a group's page, under the bar too", "LocalShellBackdrop.current" in group && "LocalShellBarInset.current" in group)
    }

    /** D-220 fix wave (I7): the tab bar overlays every page HomeShell shows, so each keeps its height clear: every
     *  composable called in HomeShell's `when (selectedTab)` either draws through `TabRootScaffold(` (which adds the
     *  inset) or reads `LocalShellBarInset.current` itself. A new page under the bar fails here until it does. */
    @Test
    fun every_page_under_the_tab_bar_keeps_it_clear() {
        val shell = main("AppRoot.kt").substringAfter("internal fun HomeShell(")
        val start = shell.indexOf("when (page.tab) {") // D-250: the tab's page reads its own place
        // D-224: the first group's overlay sits between the pages and the bar; it is not a page.
        val end = shell.indexOf("DoneOverlay(donePlay, dark)", start)
        assertTrue("the tab switch is found: $start, $end", start >= 0 && end > start)
        val pages = Regex("""^\s*(?:\} \?: if \([^)]*\) \{\s*)?(?:HomeTab\.[A-Z, .]+->\s*(?:page\.group\?\.let \{ id ->)?\s*)?([A-Z][A-Za-z]+)\(""", RegexOption.MULTILINE)
            .findAll(shell.substring(start, end)).map { it.groupValues[1] }.toSet()
        // D-249: Your spending's page, over Balance (the card) or Expenses (the Just me header).
        assertEquals(setOf("GroupPage", "SpendingScreen", "OverviewScreen", "GroupsTab", "ExpenseListScreen", "SettleAllScreen"), pages)
        val sources = AppSources.walk()
            .filter { it.isFile && it.name.endsWith(".kt") }.toList()
            .also { check(it.size > 50) { "main sources not found: ${it.size}" } }
        for (page in pages) {
            val decl = Regex("""^(?:internal |private )?fun $page\(""", RegexOption.MULTILINE)
            val file = sources.single { decl.containsMatchIn(it.readText()) }.readText()
            val body = file.substring(decl.find(file)!!.range.first).let { it.substring(0, it.indexOf("\n}\n").let { e -> if (e < 0) it.length else e }) }
            assertTrue("$page keeps the tab bar clear", "TabRootScaffold(" in body || "LocalShellBarInset.current" in body)
        }
    }
}
