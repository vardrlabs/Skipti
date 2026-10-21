package com.vardrlabs.skipti

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-217 Task 4: Android's navigation bar (SkiptiBar.kt), the twin of iOS 26/27's. The decisions are pure (the
 * composables only draw them), so they are pinned here without a device: the title sizes per form, when the inline
 * title takes over from the large one, when the bar's ground shows, and the back button's spoken name.
 */
class SkiptiBarTest {

    private fun source(): String = java.io.File("src/main/java/com/vardrlabs/skipti/SkiptiBar.kt").readText()

    @Test
    fun a_tab_roots_large_title_is_34_and_every_other_title_is_17() {
        assertEquals(34f, BarDecisions.titleSize(BarForm.TAB_ROOT, collapsed = false), 0f)
        assertEquals(17f, BarDecisions.titleSize(BarForm.TAB_ROOT, collapsed = true), 0f)
        assertEquals(17f, BarDecisions.titleSize(BarForm.PUSHED, collapsed = false), 0f)
        assertEquals(17f, BarDecisions.titleSize(BarForm.SHEET, collapsed = false), 0f)
        // They are Task 1's table, not numbers of their own.
        assertEquals(TypeScale.largeTitle, BarDecisions.titleSize(BarForm.TAB_ROOT, false), 0f)
        assertEquals(TypeScale.inlineTitle, BarDecisions.titleSize(BarForm.PUSHED, false), 0f)
    }

    @Test
    fun the_inline_title_takes_over_once_the_large_titles_bottom_is_under_the_bar() {
        // The large title's bottom sits 120 px below the bar's lower edge at scroll 0.
        assertFalse(BarDecisions.inlineTitleShown(BarForm.TAB_ROOT, scrollPx = 0, largeTitleBottomPx = 120))
        assertFalse(BarDecisions.inlineTitleShown(BarForm.TAB_ROOT, scrollPx = 119, largeTitleBottomPx = 120))
        assertTrue(BarDecisions.inlineTitleShown(BarForm.TAB_ROOT, scrollPx = 120, largeTitleBottomPx = 120))
        assertTrue(BarDecisions.inlineTitleShown(BarForm.TAB_ROOT, scrollPx = 500, largeTitleBottomPx = 120))
    }

    @Test
    fun a_pushed_or_sheet_bar_always_shows_its_title() {
        for (form in listOf(BarForm.PUSHED, BarForm.SHEET)) {
            assertTrue("$form at the top", BarDecisions.inlineTitleShown(form, scrollPx = 0, largeTitleBottomPx = 0))
            assertTrue("$form scrolled", BarDecisions.inlineTitleShown(form, scrollPx = 900, largeTitleBottomPx = 120))
        }
    }

    /** D-217 Task 8: a sheet with a LARGE title (Add and Edit expense) folds it into the bar like a tab root; the other
     *  sheets' inline titles are not that case (SheetBar's own default). */
    @Test
    fun a_large_sheet_title_folds_into_the_bar_when_it_has_scrolled_under() {
        assertFalse(BarDecisions.largeSheetInlineTitleShown(scrollPx = 0, largeTitleBottomPx = 120))
        assertFalse(BarDecisions.largeSheetInlineTitleShown(scrollPx = 119, largeTitleBottomPx = 120))
        assertTrue(BarDecisions.largeSheetInlineTitleShown(scrollPx = 120, largeTitleBottomPx = 120))
        assertTrue(BarDecisions.largeSheetInlineTitleShown(scrollPx = 900, largeTitleBottomPx = 120))
        // A sheet that draws no large title keeps its bar title at every scroll (largeTitleBottom is null there).
        val bar = source()
        assertTrue(bar.contains("if (largeTitleBottom == null) true"))
        assertTrue(bar.contains("heading = largeTitleBottom == null"))
    }

    @Test
    fun the_bars_ground_is_clear_at_the_top_and_shown_once_scrolled() {
        // One rule for every form (D-217 final fix: the unused form parameter is gone).
        assertFalse("at the top", BarDecisions.barGroundShown(scrollPx = 0))
        assertTrue("one pixel down", BarDecisions.barGroundShown(scrollPx = 1))
        assertTrue("scrolled", BarDecisions.barGroundShown(scrollPx = 400))
    }

    /** The ground reaches up under the status bar (iOS's does), so no seam shows between the strip and the bar. */
    @Test
    fun the_grounds_rectangle_runs_from_the_top_of_the_window_to_the_bars_bottom() {
        // A 63 px status bar over a 116 px bar: the ground starts 63 above the bar's top and is 179 tall.
        assertEquals(-63, BarDecisions.groundTop(63))
        assertEquals(179, BarDecisions.groundHeight(63, 116))
        // Its bottom is the bar's own bottom whatever the inset, and with no inset it is the bar alone.
        assertEquals(116, BarDecisions.groundTop(63) + BarDecisions.groundHeight(63, 116))
        assertEquals(0, BarDecisions.groundTop(0))
        assertEquals(116, BarDecisions.groundHeight(0, 116))
        // The composable reads the raw status bar inset and draws the rectangle from those two functions.
        val src = source()
        assertTrue(src.contains("WindowInsets.statusBars.getTop("))
        assertTrue(src.contains("BarDecisions.groundTop(statusTop)"))
        assertTrue(src.contains("BarDecisions.groundHeight(statusTop, groundPx)"))
    }

    /** A pushed or sheet bar reads its page's scroll inside the bar (a derived state), never as a number the
     *  caller reads, so a scroll frame does not recompose the screen that holds the bar. */
    @Test
    fun the_pushed_and_sheet_bars_take_a_scroll_state_not_a_scroll_number() {
        val src = source()
        val signatures = src.lines().filter { it.startsWith("fun PushedBar(") || it.startsWith("fun SheetBar(") }
        assertEquals(2, signatures.size)
        for (line in signatures) assertFalse("scrollPx in $line", line.contains("scrollPx"))
        assertFalse("scrollPx = in a caller", src.contains("scrollPx ="))
        // D-217 Task 7: PushedBar's signature is multi-line (a null back, and a lazy list's state beside the scroll state).
        // D-220: and the page's backdrop last.
        assertTrue(src.contains("    onBack: (() -> Unit)?,\n    trailing: (@Composable () -> Unit)? = null,\n    scroll: ScrollState? = null,\n    lazy: LazyListState? = null,\n    underStatusBar: Boolean = true,\n    backdrop: Backdrop? = null,\n) {"))
        // D-217 Task 8: SheetBar's signature is multi-line too (a cancel label, a large title's bottom, the on-surface pill).
        assertTrue(src.contains("    doneEnabled: Boolean = true,\n    scroll: ScrollState? = null,\n    cancelLabel: String = BarDecisions.CANCEL_LABEL,"))
        // Three bars read the ground inside a derived state (the pushed one reads a scroll state or a lazy list's).
        assertEquals("derived ground reads", 3, src.split("BarDecisions.barGroundShown(").size - 1)
        assertEquals("derived states for the ground", 3, Regex("""val groundOn by remember\([^)]*\) \{\s*derivedStateOf""").findAll(src).count())
    }

    /** The content's top padding is one constant: the bar and 8 dp, not a sum written out in each place. */
    @Test
    fun the_content_top_padding_is_one_named_constant() {
        assertEquals(52, BarDecisions.CONTENT_TOP_PAD)
        assertEquals(BarDecisions.BAR_HEIGHT + 8, BarDecisions.CONTENT_TOP_PAD)
        val src = source()
        assertFalse(src.contains("BAR_HEIGHT.dp + 8.dp"))
        // D-217 look fix 1: the tab root's alone now (the scaffold's padding and its collapse arithmetic) and the proof
        // rows'; a pushed page and a sheet start where iOS's container does ([scrollTop], [formTop], [LARGE_SHEET_TOP]).
        assertEquals(3, src.split("BarDecisions.CONTENT_TOP_PAD").size - 1)
        assertEquals(1, src.split("= CONTENT_TOP_PAD + LARGE_SHEET_TITLE_EXTRA").size - 1)
    }

    /** D-217 look fix 1: where the first content starts under the bar, one value per kind of iOS container, measured on
     *  iOS 27 against the look's pairs (title line to first content; Android's old 8 dp gap was 17.5 pt short under a
     *  padding-16 ScrollView, 21 pt under a padding-20 one, 36 pt under a headerless Form, 12 pt under a Form's header). */
    @Test
    fun the_first_content_starts_where_its_ios_container_starts_it() {
        // The bar leaves the scroll content 9.5 under its 44, and the page's own padding comes on top.
        assertEquals(9.5f, BarDecisions.IOS_BAR_CLEARANCE, 0f)
        assertEquals(69.5f, BarDecisions.scrollTop(16f), 0f)
        assertEquals(73.5f, BarDecisions.scrollTop(20f), 0f)
        // The Android gap before (CONTENT_TOP_PAD, 52) fell short by the measured amounts.
        assertEquals(17.5f, BarDecisions.scrollTop(16f) - BarDecisions.CONTENT_TOP_PAD, 0f)
        assertEquals(21.5f, BarDecisions.scrollTop(20f) - BarDecisions.CONTENT_TOP_PAD, 0f)
        // A Form: its first header 20 under the bar (+12 over the old gap), its header 24 tall (+36 for a headerless card).
        assertEquals(64f, BarDecisions.formTop(headerless = false), 0f)
        assertEquals(88f, BarDecisions.formTop(headerless = true), 0f)
        assertEquals(12f, BarDecisions.formTop(false) - BarDecisions.CONTENT_TOP_PAD, 0f)
        assertEquals(36f, BarDecisions.formTop(true) - BarDecisions.CONTENT_TOP_PAD, 0f)
        // A sheet's large title sits 4.5 lower than a tab root's.
        assertEquals(56.5f, BarDecisions.LARGE_SHEET_TOP, 0f)
        // A tab root: Balance, Expenses and Settle up pad 16, Groups 8, in every theme (Norse's Balance measured 8 tight too).
        assertEquals(8, BarDecisions.tabRootExtra(BarDecisions.TAB_ROOT_ROOMY_PADDING))
        assertEquals(0, BarDecisions.tabRootExtra(BarDecisions.TAB_ROOT_BASE_PADDING))
    }

    /** The iOS containers those values come from, read from the Swift (so a change there turns this red). */
    @Test
    fun the_containers_the_values_come_from_are_in_the_swift() {
        val detail = swift("ExpenseDetailView.swift")
        assertTrue(detail.contains("ScrollView { content(expense) }"))
        assertTrue(detail.contains("        .padding(16)\n        .frame(maxWidth: .infinity, alignment: .leading)\n    }"))
        assertTrue(swift("OverviewScreen.swift").contains("                    .padding(16)\n                    .shellScrollTop() // D-205"))
        assertTrue(swift("ExpenseListView.swift").contains("                    .padding(16)\n                    .shellScrollTop() // D-205"))
        assertTrue(swift("SettleAll.swift").contains("            .padding(16)\n            .shellScrollTop() // D-205"))
        assertTrue(swift("GroupsScreen.swift").contains("            .padding(.vertical, 8)\n            .shellScrollTop() // D-205"))
        assertTrue(swift("AccountHubScreen.swift").contains("            .padding(20)\n            .frame(maxWidth: .infinity, alignment: .leading)"))
        // D-225 T4: Group settings left the Form for list cards on a ScrollView, its first card where the Form put it
        // (the currency list's top, CurrencyListScreen.topPadding: Android's formTop(headerless = true)).
        val groupSettings = swift("GroupSettings.swift")
        assertTrue(groupSettings.contains("        ScrollView {\n            VStack(alignment: .leading, spacing: 16) {\n                if let named {\n                    ListCard {"))
        assertTrue(groupSettings.contains("            .padding(.horizontal, 16)\n            .padding(.top, CurrencyListScreen.topPadding)"))
        assertTrue(swift("CurrencyChoice.swift").contains("static let topPadding: CGFloat = 34.5"))
        assertTrue(swift("GroupSheets.swift").contains("header(\"Group\")"))
        // The Android pages ask for those values by kind, in one place each.
        val bar = source()
        assertTrue(bar.contains("top: Dp = BarDecisions.scrollTop(side.value).dp,"))
        assertTrue(bar.contains("top: Dp = (if (largeTitle) BarDecisions.LARGE_SHEET_TOP else BarDecisions.scrollTop(side.value)).dp,"))
        assertTrue(bar.contains("val topPadPx = with(LocalDensity.current) { top.roundToPx() }"))
        assertTrue(bar.contains("val extraTop = BarDecisions.tabRootExtra(iosPadding)"))
        for (file in listOf("OverviewTab.kt", "SettleAll.kt")) {
            assertTrue("$file pads 16", screen(file).contains("iosPadding = BarDecisions.TAB_ROOT_ROOMY_PADDING,"))
        }
        assertTrue(screen("ExpenseListScreen.kt").contains("iosPadding = BarDecisions.TAB_ROOT_ROOMY_PADDING,"))
        assertFalse("Groups keeps iOS's 8", screen("GroupsTab.kt").contains("iosPadding"))
        assertTrue(screen("GroupSettings.kt").contains("top = BarDecisions.formTop(headerless = true).dp"))
        assertTrue(screen("ImportFlow.kt").contains("top = BarDecisions.formTop(headerless = true).dp"))
        assertEquals(2, screen("GroupScreens.kt").split("top = BarDecisions.formTop(headerless = false).dp").size - 1)
        assertTrue(screen("About.kt").contains("top = BarDecisions.scrollTop(20f).dp"))
        assertTrue(screen("ExportScreen.kt").contains("top = BarDecisions.scrollTop(16f).dp"))
        // The settings pages space their items 16 apart, none above the first.
        assertTrue(screen("AccountHubScreen.kt").contains("if (index > 0) Spacer(Modifier.height(16.dp))"))
    }

    /** D-217 look fix 3: the pages the first wave left on the old 52 dp take their iOS container's top as well: the
     *  removal flow's steps (iOS ScrollViews at padding 16), the sign-in confirmation (a VStack at padding 20) and the
     *  currency list (a List whose first section, the search field, has no header; D-220 fix wave I4: iOS's is now a
     *  ScrollView of the theme's cards, its first card where the List's was, Android's `formTop(headerless = true)`). */
    @Test
    fun the_last_pages_on_the_old_top_padding_take_their_containers() {
        val removal = swift("RemovalFlow.swift")
        assertEquals(5, removal.split("        ScrollView {\n").size - 1)
        assertTrue(removal.contains("            .padding(16)\n            .frame(maxWidth: .infinity, alignment: .leading)\n        }\n        .auroraBackground()"))
        assertTrue(swift("AccountOwner.swift").contains("        .padding(20)\n        .frame(maxWidth: .infinity, maxHeight: .infinity)\n        .auroraBackground() // D-176"))
        val currencies = swift("CurrencyChoice.swift")
        assertTrue(currencies.contains("        ScrollView {\n            VStack(alignment: .leading, spacing: 8) {"))
        assertTrue(currencies.contains(".padding(.top, Self.topPadding)"))
        val flow = screen("RemovalFlow.kt").substringAfter("internal fun FlowScreen(").substringBefore("\n}\n")
        assertTrue(flow.contains("top = BarDecisions.scrollTop(16f).dp"))
        assertTrue(screen("AccountOwner.kt").contains("padding(start = 24.dp, end = 24.dp, top = BarDecisions.scrollTop(20f).dp, bottom = 24.dp)"))
        assertTrue(screen("ListRows.kt").contains("top = BarDecisions.formTop(headerless = true).dp"))
        for (file in listOf("RemovalFlow.kt", "AccountOwner.kt", "CurrencyChoice.kt")) {
            assertFalse("$file no longer pads the old 52", screen(file).contains("CONTENT_TOP_PAD"))
        }
    }

    /** D-217 look fix 3: the bar's glyphs and pills at iOS 26's measured sizes (the pairs: back circle 44.7 pt, Cancel
     *  pill 44 pt tall and 81 pt wide round a 45.6 pt label, account glyph the same circle round a 30 pt badge). */
    @Test
    fun the_bars_circles_and_pills_are_ios_sizes() {
        // D-220 fix wave (F4): 44, iOS 27's circle measured 44.0 tall on the T6 look.
        assertEquals(44, BarDecisions.BACK_BUTTON_SIZE)
        assertEquals(44, BarDecisions.ACCOUNT_CIRCLE_SIZE)
        assertEquals(30, BarDecisions.ACCOUNT_BADGE_SIZE)
        assertEquals(0.73f, BarDecisions.ACCOUNT_GLYPH_SCALE, 0f)
        assertEquals(21.9f, BarDecisions.ACCOUNT_BADGE_SIZE * BarDecisions.ACCOUNT_GLYPH_SCALE, 0.001f)
        // A 45 dp circle centred on the 44 dp bar starts half a dp above it; the 44 at its top.
        assertEquals(-0.5f, BarDecisions.roundTop(45), 0f)
        assertEquals(0f, BarDecisions.roundTop(44), 0f)
        assertEquals(4f, BarDecisions.roundTop(36), 0f)
        assertEquals(44, BarDecisions.PILL_HEIGHT)
        assertEquals(16, BarDecisions.PILL_SIDE_PADDING)
        assertEquals(10, BarDecisions.PILL_VERTICAL_PADDING)
        val src = source()
        val back = src.substringAfter("private fun BackButton(").substringBefore("\n}\n")
        assertTrue(back.contains("BarCircle(dark, BarDecisions.BACK_BUTTON_SIZE) {"))
        assertTrue(src.contains(".offset(y = BarDecisions.roundTop(sizeDp).dp)"))
        val shell = screen("AppRoot.kt").substringAfter("internal fun AccountGlyph(").substringBefore("\n}\n")
        // D-220 (A1): the glyph's bubble is iOS's measured capsule round a badge, its circle round the plain glyph.
        assertTrue(shell.contains("BarCircle(dark, BarDecisions.accountBubbleHeight(badge), width = BarDecisions.accountBubbleWidth(badge)) {"))
        assertTrue(shell.contains("modifier = Modifier.size((BarDecisions.ACCOUNT_BADGE_SIZE * BarDecisions.ACCOUNT_GLYPH_SCALE).dp)"))
        assertTrue(shell.contains("avatar, name, BarDecisions.ACCOUNT_BADGE_SIZE.dp, dark,"))
        // iOS: the badge is 30 and the plain glyph its 0.73 (D-221 S12: the bar's badge is drawn in its original colours).
        val ios = swift("HomeShell.swift")
        assertTrue(ios.contains("ProfileBadge(store: LedgerStore.defaultStore(), size: 30, original: true)"))
        assertTrue(swift("Profile.swift").contains("LucideIcon(glyph: .userRound, size: size * 0.73)"))
    }

    /** D-217 look fix 3 (re-review Minor 1): a pill is 44 dp tall at the default text size and GROWS with the text, so
     *  its label is never clipped (at font scale 2.0 a 17 sp label is 34 dp or more of line). */
    @Test
    fun a_pill_grows_with_the_text_and_never_clips_its_label() {
        // Font scale 1.0: a 17 sp label is about 22 dp of line, the 44 dp floor holds.
        assertEquals(44f, BarDecisions.pillHeight(lineDp = 22f), 0f)
        assertEquals(44f, BarDecisions.pillHeight(lineDp = 0f), 0f)
        // At the floor's edge: 24 dp of line plus 10 each side is exactly 44.
        assertEquals(44f, BarDecisions.pillHeight(lineDp = 24f), 0f)
        // Font scale 2.0: a 34 sp label is about 44 dp of line, so the pill is 64 dp: taller than its label.
        assertEquals(64f, BarDecisions.pillHeight(lineDp = 44f), 0f)
        assertTrue(BarDecisions.pillHeight(lineDp = 44f) >= 44f + 2 * BarDecisions.PILL_VERTICAL_PADDING)
        // The node holds the tallest control, never less than the 48 dp target.
        assertEquals(144, BarDecisions.nodeHeight(minPx = 126, startPx = 144, endPx = 90))
        assertEquals(126, BarDecisions.nodeHeight(minPx = 126, startPx = 0, endPx = 0))
        val pill = source().substringAfter("private fun BarPill(").substringBefore("\n}\n")
        assertTrue(pill.contains(".heightIn(min = BarDecisions.PILL_HEIGHT.dp)"))
        assertTrue(pill.contains(".padding(horizontal = BarDecisions.PILL_SIDE_PADDING.dp, vertical = BarDecisions.PILL_VERTICAL_PADDING.dp)"))
        assertFalse("no fixed pill height", pill.contains(".height(BarDecisions.PILL_HEIGHT.dp)"))
        // The row measures controls without a height limit and sizes its node from them.
        val row = source().substringAfter("private fun BarRow(").substringBefore("\n}\n")
        assertTrue(row.contains("maxHeight = Constraints.Infinity"))
        assertTrue(row.contains("layout(width, BarDecisions.nodeHeight(BarDecisions.TOUCH_TARGET.dp.roundToPx(), s?.height ?: 0, e?.height ?: 0)) {"))
        assertTrue(row.contains("modifier = Modifier.fillMaxWidth(),"))
    }

    @Test
    fun the_back_button_is_spoken_as_back_and_the_pills_as_cancel_and_done() {
        assertEquals("Back", BarDecisions.BACK_LABEL)
        assertEquals("Cancel", BarDecisions.CANCEL_LABEL)
        assertEquals("Done", BarDecisions.DONE_LABEL)
        // The composable reads the constant for its description.
        assertTrue(source().contains("contentDescription = BarDecisions.BACK_LABEL"))
    }

    /** Both titles of the bar take their style from the one title decision, at the table's two sizes. */
    @Test
    fun the_bar_takes_its_title_style_only_from_Type_title() {
        val src = source()
        // The sizes come from the one decision (BarDecisions.titleSize), which reads Task 1's table: the composables
        // never name a size of their own, so the tested function is the live one.
        assertTrue(src.contains("Type.title(dark, BarDecisions.titleSize(BarForm.TAB_ROOT, collapsed = false))"))
        assertTrue(src.contains("Type.title(dark, BarDecisions.titleSize(form, collapsed = true))"))
        assertFalse("a title size named in a composable", Regex("""Type\.title\(dark, TypeScale""").containsMatchIn(src))
        for (other in listOf("Type.display(", "Type.label(", "Type.name(", "Type.microLabel(", "Type.kicker(")) {
            assertFalse("SkiptiBar.kt must not style a title with $other", src.contains(other))
        }
        // No size, weight or tracking of its own on a title.
        assertFalse(Regex("""Type\.title\([^)]*\)\.copy\(""").containsMatchIn(src))
    }

    /** The back button is a drawing and a description, never a word on screen (iOS draws the chevron alone). */
    @Test
    fun the_bar_draws_no_back_word() {
        val src = source()
        assertFalse(src.contains("Text(\"Back\""))
        assertFalse(src.contains("TextButton"))
        assertTrue(src.contains("TabIcons.ChevronLeft"))
    }

    /** D-217 final fix (I3): TalkBack reads the bar first, as VoiceOver reads iOS's navigation bar first: the content
     *  fills the page from its top, so in Compose's geometric order it would come before the bar that sits over it.
     *  The bar is its own traversal group, ordered before its siblings (the page's content). */
    @Test
    fun talkback_reads_the_bar_before_the_page() {
        assertEquals(-1f, BarDecisions.BAR_TRAVERSAL_INDEX, 0f)
        assertTrue("before the default 0", BarDecisions.BAR_TRAVERSAL_INDEX < 0f)
        val src = source()
        val frame = src.substringAfter("private fun BarFrame(").substringBefore("\n}\n")
        assertTrue(frame.contains("Box(\n        Modifier\n            .fillMaxWidth()\n            .height(BarDecisions.BAR_HEIGHT.dp)\n            .semantics {\n                isTraversalGroup = true\n                traversalIndex = BarDecisions.BAR_TRAVERSAL_INDEX\n            },\n    ) {"))
        // Every bar goes through BarFrame (the tab root's, the pushed one and the sheet's).
        assertEquals(3, src.split("    BarFrame(").size - 1)
    }

    /** D-217 final fix (I6): the centred title is measured against the bar's controls, not a fixed reserve: at a large
     *  text size a Cancel pill is far wider than any reserve, and the title drew under it. Centred on the bar, the
     *  title may be as wide as the bar less the WIDER control on both sides and a gap each side. */
    @Test
    fun the_title_clears_the_wider_control_on_both_sides() {
        // A 396 px bar, a 48 px back button and nothing at the end, an 8 px gap: 396 - 2 * 48 - 16.
        assertEquals(284, BarDecisions.titleMaxWidth(396, leading = 48, trailing = 0, gap = 8))
        // Symmetric whichever side is wide: a 140 px Cancel at the start, or a 140 px Done at the end.
        assertEquals(100, BarDecisions.titleMaxWidth(396, leading = 140, trailing = 0, gap = 8))
        assertEquals(100, BarDecisions.titleMaxWidth(396, leading = 48, trailing = 140, gap = 8))
        // No controls: the bar less the two gaps.
        assertEquals(380, BarDecisions.titleMaxWidth(396, leading = 0, trailing = 0, gap = 8))
        // Controls wider than the bar can share: no room, never negative.
        assertEquals(0, BarDecisions.titleMaxWidth(396, leading = 200, trailing = 10, gap = 8))
        assertEquals(8, BarDecisions.TITLE_GAP)
        // Vertical places: a control or title shorter than the 44 px bar is centred on it; the 48 px targets sit at the top.
        assertEquals(5, BarDecisions.centredTop(height = 34, barPx = 44))
        assertEquals(0, BarDecisions.centredTop(height = 48, barPx = 44))
        assertEquals(0, BarDecisions.centredTop(height = 44, barPx = 44))
        // D-217 look fix 3: a pill taller than the 48 dp target (a large text size) is centred on the bar, which grows
        // with it: the lane is the tallest control's height then, the bar's own otherwise (the default is unchanged).
        assertEquals(44, BarDecisions.laneHeight(tallestPx = 48, barPx = 44, targetPx = 48))
        assertEquals(44, BarDecisions.laneHeight(tallestPx = 0, barPx = 44, targetPx = 48))
        assertEquals(64, BarDecisions.laneHeight(tallestPx = 64, barPx = 44, targetPx = 48))
        assertEquals(0, BarDecisions.centredTop(height = 64, barPx = 64))
        assertEquals(8, BarDecisions.centredTop(height = 48, barPx = 64))
        assertEquals(10, BarDecisions.centredTop(height = 44, barPx = 64))
        val laneRow = source().substringAfter("private fun BarRow(").substringBefore("\n}\n")
        assertTrue(laneRow.contains("val lane = BarDecisions.laneHeight(maxOf(s?.height ?: 0, e?.height ?: 0), barPx, BarDecisions.TOUCH_TARGET.dp.roundToPx())"))
        assertEquals(3, laneRow.split("BarDecisions.centredTop(").size - 1)
        assertEquals(3, laneRow.split(", lane))").size - 1)
        assertTrue(source().contains("val groundPx = BarDecisions.laneHeight(size.height.toInt(), barPx.toInt(), BarDecisions.TOUCH_TARGET.dp.roundToPx())"))
        // Horizontally the title is centred on the bar (so on the screen), not between the controls.
        assertEquals(98, BarDecisions.titleLeft(width = 396, titleWidth = 200))
        val src = source()
        assertFalse("the fixed reserve", src.contains("titleSideReserve"))
        val frame = src.substringAfter("private fun BarRow(").substringBefore("\n}\n")
        assertTrue(frame.contains("Layout(\n        contents = listOf(start ?: {}, end ?: {}, title),"))
        assertTrue(frame.contains("val maxTitle = BarDecisions.titleMaxWidth(width, s?.width ?: 0, e?.width ?: 0, BarDecisions.TITLE_GAP.dp.roundToPx())"))
        assertTrue(frame.contains("titleMeasurables.firstOrNull()?.measure(Constraints(maxWidth = maxTitle))"))
        assertTrue(frame.contains("t?.placeRelative(BarDecisions.titleLeft(width, t.width), BarDecisions.centredTop(t.height, lane))"))
        // The three bars hand their controls to the measured row: the back button, the trailing item, the pills.
        assertTrue(src.contains("start = if (onBack != null) ({ BackButton(dark, onBack) }) else null,"))
        assertTrue(src.contains("start = { BarPill(cancelLabel, dark, true, onSurface, onCancel) },"))
        assertTrue(src.contains("end = if (onDone != null) ({ BarPill(BarDecisions.DONE_LABEL, dark, doneEnabled, onSurface, onDone) }) else null,"))
        assertTrue(src.contains("BarFrame(dark, groundOn, end = trailing, backdrop = backdrop)"))
    }

    /** The sheet pills' touch target is the bar's 48 dp node, as the back button's is (T4 Minor 4), its pill centred
     *  on the 44 dp bar. */
    @Test
    fun the_pills_touch_target_is_48_dp() {
        val pill = source().substringAfter("private fun BarPill(").substringBefore("\n}\n")
        // D-217 look fix 3: at least 48 dp, the 44 dp pill at its top (the bar's own height), growing with the text.
        assertTrue(pill.contains(".heightIn(min = BarDecisions.TOUCH_TARGET.dp)"))
        assertTrue(pill.contains(".heightIn(min = BarDecisions.PILL_HEIGHT.dp)"))
        assertEquals(44, BarDecisions.PILL_HEIGHT)
        assertEquals(BarDecisions.BAR_HEIGHT, BarDecisions.PILL_HEIGHT)
        assertFalse(pill.contains(".height(BarDecisions.BAR_HEIGHT.dp)"))
    }

    /** D-217 final fix (T8 Minor 1): a bar drawn inside a bottom sheet or a dialog window is not under the status bar,
     *  so its scrolled ground must not reach up by the inset (it would paint over the sheet's drag handle). */
    @Test
    fun a_bar_in_a_sheet_or_dialog_window_does_not_reach_under_the_status_bar() {
        assertEquals(63, BarDecisions.statusTopPx(underStatusBar = true, insetPx = 63))
        assertEquals(0, BarDecisions.statusTopPx(underStatusBar = false, insetPx = 63))
        assertEquals(0, BarDecisions.groundTop(BarDecisions.statusTopPx(false, 63)))
        assertEquals(116, BarDecisions.groundHeight(BarDecisions.statusTopPx(false, 63), 116))
        val src = source()
        assertTrue(src.contains("val statusTop = BarDecisions.statusTopPx(underStatusBar, WindowInsets.statusBars.getTop(LocalDensity.current))"))
        // The person sheet's bar (on the surface, in a ModalBottomSheet) and the currency dialog's.
        // D-217 look fix 5: the person sheet's bar says both facts by name: its pills sit on the surface (onSurface) and it
        // is not under the status bar (underStatusBar), two flags that each mean one thing.
        assertEquals(2, src.split("BarFrame(\n        dark, groundOn, underStatusBar = underStatusBar,").size - 1)
        assertFalse("onSurface no longer decides the inset", src.contains("underStatusBar = !onSurface"))
        assertTrue(src.contains("    onSurface: Boolean = false,\n    underStatusBar: Boolean = true,\n    backdrop: Backdrop? = null,\n) {"))
        val removal = java.io.File("src/main/java/com/vardrlabs/skipti/RemovalFlow.kt").readText()
        assertTrue(removal.contains("SheetBar(name, onCancel = onCancel, scroll = scroll, onSurface = true, underStatusBar = false)"))
        val currency = java.io.File("src/main/java/com/vardrlabs/skipti/CurrencyChoice.kt").readText()
        // D-220 (L-D217-6): the currency list's window now runs under the status bar, so its bar reaches up as a page's does.
        // D-225: the window is the shared PushedWindow (ListRows.kt), which draws the bar with the title it is given.
        val window = java.io.File("src/main/java/com/vardrlabs/skipti/ListRows.kt").readText()
        assertTrue(currency.contains("PushedWindow(CurrencyCopy.ALL_CURRENCIES, dark, onClose)"))
        assertTrue(window.contains("PushedBar(title, onBack = onClose, scroll = scroll, backdrop = backdrop)"))
    }

    /** D-217 final fix (T4R-27): the 48 dp node behind the back button's target: the bar is 44 dp in the layout, its
     *  node 48 dp tall from the bar's top (unbounded), and the back button fills it. */
    @Test
    fun the_back_buttons_48_dp_target_is_the_bars_48_dp_node() {
        assertEquals(48, BarDecisions.TOUCH_TARGET)
        assertEquals(44, BarDecisions.BAR_HEIGHT)
        val src = source()
        val frame = src.substringAfter("private fun BarFrame(").substringBefore("\n}\n")
        // D-217 look fix 3: at least that tall; a pill at a large text size makes the node taller.
        assertTrue(frame.contains(".wrapContentHeight(Alignment.Top, unbounded = true)\n                .heightIn(min = BarDecisions.TOUCH_TARGET.dp)"))
        val back = src.substringAfter("private fun BackButton(").substringBefore("\n}\n")
        assertTrue(back.contains(".size(BarDecisions.TOUCH_TARGET.dp)"))
    }

    /** D-217 final fix (Minors 5 and 7): the large title's bottom is one decision with numbers, used by the tab root
     *  and the large sheet alike; the sheet keeps one lambda for it across recompositions; an empty title is no
     *  heading (TalkBack would announce an empty one). */
    @Test
    fun the_large_titles_bottom_and_the_titles_heading_are_decisions() {
        // A title at 0 in the column, 120 px tall, under a 156 px top padding: its bottom is 276 px down.
        assertEquals(276, BarDecisions.largeTitleBottomPx(positionY = 0, heightPx = 120, topPadPx = 156))
        assertEquals(306, BarDecisions.largeTitleBottomPx(positionY = 30, heightPx = 120, topPadPx = 156))
        val src = source()
        assertEquals(2, src.split("titleBottom = BarDecisions.largeTitleBottomPx(it.positionInParent().y.roundToInt(), it.size.height, topPadPx)").size - 1)
        assertFalse(src.contains("it.positionInParent().y.roundToInt() + it.size.height + topPadPx"))
        assertTrue(src.contains("val largeBottom = remember { { titleBottom } }"))
        assertTrue(src.contains("largeTitleBottom = if (largeTitle) largeBottom else null"))
        assertTrue(BarDecisions.titleIsHeading(heading = true, title = "Join a group"))
        assertFalse(BarDecisions.titleIsHeading(heading = true, title = ""))
        assertFalse(BarDecisions.titleIsHeading(heading = false, title = "Balance"))
        assertTrue(src.contains(".then(if (BarDecisions.titleIsHeading(heading, title)) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}),"))
    }

    /** D-220 (L-D217-3): the bar's ground per theme. Aurora's glass blurs the page under it (Backdrop.kt) beneath the
     *  decided film; Norse and Vardr Labs draw the solid surface. Every page that scrolls under a bar is its source; the
     *  round controls and the pills carry a hairline ring (iOS 26's glass has a rim; on a scrolled surface bar Android's
     *  circle vanished, al-13); the pinned action is backed with the page colour in the control shape. */
    @Test
    fun the_bar_ground_follows_the_theme() {
        val src = source()
        val frame = src.substringAfter("private fun BarFrame(").substringBefore("\n}\n")
        assertTrue("the film decision", frame.contains("TabBarGround.film("))
        assertTrue("the glass", frame.contains("backdropGlass("))
        assertFalse("no film literal", src.contains("0.94f"))
        for (page in listOf("fun TabRootScaffold(", "fun PushedPage(", "fun SheetPage(")) {
            val body = src.substringAfter(page).substringBefore("\n}\n")
            assertTrue("$page records its page", body.contains("backdropSource("))
        }
        for (control in listOf("internal fun BarCircle(", "private fun BarPill(")) {
            val body = src.substringAfter(control).substringBefore("\n}\n")
            assertTrue("$control has the hairline ring", body.contains("border(1.dp, Tok.hairline(dark)"))
        }
        val action = src.substringAfter("fun FormActionBar(").substringBefore("\n}\n")
        assertTrue("the action bar is backed with the page colour", action.contains(".background(Tok.bg("))
        assertTrue("in the control shape", action.contains("ThemeArt.controlShape("))
    }

    /** D-220 (A1): iOS 26 draws the account item and the back button on its bar glass: white over the page, about 0.13
     *  in the dark sets and 0.31 in the light ones (measured on the D-219 pairs, .drafts/d220/t5/a1-measure.py), a 51 x 44
     *  capsule round the 30 pt badge and the 45 circle round the plain glyph. Android drew a flat surface circle. */
    @Test
    fun the_account_bubble_is_ios_glass() {
        assertEquals(51, BarDecisions.ACCOUNT_BUBBLE_WIDTH)
        assertEquals(44, BarDecisions.ACCOUNT_BUBBLE_HEIGHT)
        assertEquals(51, BarDecisions.accountBubbleWidth(badge = true))
        assertEquals(44, BarDecisions.accountBubbleHeight(badge = true))
        assertEquals(BarDecisions.ACCOUNT_CIRCLE_SIZE, BarDecisions.accountBubbleWidth(badge = false))
        assertEquals(BarDecisions.ACCOUNT_CIRCLE_SIZE, BarDecisions.accountBubbleHeight(badge = false))
        assertEquals(0.13f, BarDecisions.glassFillAlpha(dark = true), 0f)
        assertEquals(0.31f, BarDecisions.glassFillAlpha(dark = false), 0f)
        val circle = source().substringAfter("internal fun BarCircle(").substringBefore("\n}\n")
        assertTrue("white at the measured alpha", circle.contains("Color.White.copy(alpha = BarDecisions.glassFillAlpha(dark))"))
        assertFalse("no surface disc", circle.contains("Tok.surface(dark)"))
        val glyph = screen("AppRoot.kt").substringAfter("internal fun AccountGlyph(").substringBefore("\n}\n")
        assertTrue("the badge decides the bubble", glyph.contains("val badge = !(avatar == Avatar.None && ProfileRules.initials(name) == null)"))
    }

    /** D-220 fix wave (F4): the bar's controls where iOS 27 puts them (centres 38 dp from the leading edge for the back
     *  circle, 41 from the trailing one for the account item, measured on the T6 look; Android's sat at 32), each a
     *  48 dp target moved in from the 8 dp side margin; the account badge in iOS's bar colours, measured per set
     *  (.drafts/d220/fix/android/badge-measure.py). */
    @Test
    fun the_bar_controls_sit_where_ios_puts_them() {
        assertEquals(8, BarDecisions.BAR_SIDE_MARGIN)
        assertEquals(38, BarDecisions.BACK_CENTRE_FROM_EDGE)
        assertEquals(41, BarDecisions.ACCOUNT_CENTRE_FROM_EDGE)
        assertEquals(6, BarDecisions.controlShift(BarDecisions.BACK_CENTRE_FROM_EDGE))
        assertEquals(9, BarDecisions.controlShift(BarDecisions.ACCOUNT_CENTRE_FROM_EDGE))
        // The centre: the margin, the shift and half the target.
        assertEquals(38, BarDecisions.BAR_SIDE_MARGIN + BarDecisions.controlShift(38) + BarDecisions.TOUCH_TARGET / 2)
        val src = source()
        assertTrue("the frame's margin is the decision", src.contains("private val BarSideMargin = BarDecisions.BAR_SIDE_MARGIN.dp"))
        val back = src.substringAfter("private fun BackButton(").substringBefore("\n}\n")
        assertTrue("the back target moves in", Regex("""\.padding\(start = BarDecisions\.controlShift\(BarDecisions\.BACK_CENTRE_FROM_EDGE\)\.dp\)\s*\.size\(BarDecisions\.TOUCH_TARGET\.dp\)""").containsMatchIn(back))
        val glyph = screen("AppRoot.kt").substringAfter("internal fun AccountGlyph(").substringBefore("\n}\n")
        assertTrue("the account target moves in", Regex("""\.padding\(end = BarDecisions\.controlShift\(BarDecisions\.ACCOUNT_CENTRE_FROM_EDGE\)\.dp\)\s*\.size\(BarDecisions\.TOUCH_TARGET\.dp\)""").containsMatchIn(glyph))
        // D-221 (Q2 a): the badge is the accent avatar (ThemeArtTest.avatars_take_their_fill); D-220's measured bar
        // colours for the old raised disc are gone with it.
        assertTrue("the badge takes the accent", glyph.contains("fill = AvatarStyle.fill(badge = true)"))
        assertFalse("no measured disc", screen("SkiptiBar.kt").contains("accountBadgeFill") || screen("SkiptiBar.kt").contains("accountBadgeRing"))
    }

    /** D-220 fix wave (F5): Aurora's navigation bar over its blur lets the glow through as iOS's material does: white at
     *  0.29 in light and 0.044 in dark (.drafts/d220/fix/android/film-measure.py), not the 0.72 surface film that read
     *  near white; solid themes and a bar that cannot blur keep the surface film. */
    @Test
    fun the_aurora_bar_film_lets_the_glow_through() {
        assertEquals(0.29f, BarDecisions.navFilmWhite(dark = false), 0f)
        assertEquals(0.044f, BarDecisions.navFilmWhite(dark = true), 0f)
        assertTrue(BarDecisions.navFilmIsWhite(AppTheme.AURORA, blur = true))
        assertFalse(BarDecisions.navFilmIsWhite(AppTheme.AURORA, blur = false))
        assertFalse(BarDecisions.navFilmIsWhite(AppTheme.NORSE, blur = true))
        assertFalse(BarDecisions.navFilmIsWhite(AppTheme.VARDR, blur = true))
        val frame = source().substringAfter("private fun BarFrame(").substringBefore("\n}\n")
        assertTrue(
            "the frame draws the decision",
            Regex("""val film = if \(BarDecisions\.navFilmIsWhite\(theme, blur\)\) \{\s*Color\.White\.copy\(alpha = BarDecisions\.navFilmWhite\(dark\)\)\s*\} else \{\s*Tok\.surface\(dark\)\.copy\(alpha = TabBarGround\.film\(theme, blur\)\)""").containsMatchIn(frame),
        )
    }

    /** D-226 (A2, .drafts/d226/art.py section 54): under Aurora's dark navigation bar the collapsed title keeps its floor
     *  over the brightest thing that can scroll under it, under the bar's white 0.044 film. Fix wave 2 (F2, art.py 55):
     *  the bar caps what it shows, a DARKEN at iOS's band colour less the film, drawn over the blurred page (on the art)
     *  and under the film, so light content reads as iOS's band (#53696A) and nothing darker changes. The title's worst
     *  ground is the film over the cap itself, for EVERY content; the floor is 5.2:1 (iOS's band exactly gives 5.19, so
     *  the cap is the fewest 8-bit steps under it that hold, one on green). Re-derived here in 8-bit, not read from the
     *  generator. Light, the solid themes and a bar that cannot blur draw the page as it is (white: no cap). */
    @Test
    fun the_dark_bar_keeps_the_titles_floor() {
        fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        fun lum(c: DoubleArray) = 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
        fun q(c: DoubleArray) = DoubleArray(3) { Math.round(c[it] * 255) / 255.0 }
        fun ratio(a: DoubleArray, b: DoubleArray): Double {
            val (x, y) = lum(q(a)) to lum(q(b))
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }
        fun rgb(hex: Int) = doubleArrayOf((hex shr 16 and 255) / 255.0, (hex shr 8 and 255) / 255.0, (hex and 255) / 255.0)
        val t = Tok.text.auroraDark
        val title = doubleArrayOf(t.red.toDouble(), t.green.toDouble(), t.blue.toDouble())
        val white = BarDecisions.navFilmWhite(dark = true).toDouble()
        // The band: the film over each channel of the content capped by the cap (Darken: the smaller of the two).
        fun band(cap: DoubleArray, content: DoubleArray) = q(DoubleArray(3) { minOf(cap[it], content[it]) + (1 - minOf(cap[it], content[it])) * white })
        val c = BarDecisions.navBandCap(AppTheme.AURORA, dark = true, blur = true)
        val cap = doubleArrayOf(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble())
        assertEquals(1f, c.alpha, 0f)
        assertEquals("8-bit #4B6163", listOf(0x4B, 0x61, 0x63), cap.map { Math.round(it * 255).toInt() })
        val ios = rgb(0x53696A)
        val overWhite = band(cap, doubleArrayOf(1.0, 1.0, 1.0))
        assertTrue("over light content the band is iOS's within a step: ${overWhite.toList()}",
            (0..2).all { Math.abs(Math.round(overWhite[it] * 255) - Math.round(ios[it] * 255)) <= 1 })
        assertTrue("the title holds 5.2 over white content: ${ratio(title, overWhite)}", ratio(title, overWhite) >= 5.2)
        assertTrue("over black content", ratio(title, band(cap, doubleArrayOf(0.0, 0.0, 0.0))) >= 5.2)
        var worst = Double.MAX_VALUE
        for (r in 0..15) for (g in 0..15) for (b in 0..15) {
            worst = minOf(worst, ratio(title, band(cap, doubleArrayOf(r / 15.0, g / 15.0, b / 15.0))))
        }
        assertTrue("over every content (16 steps a channel): $worst", worst >= 5.2)
        val exact = DoubleArray(3) { (ios[it] - white) / (1 - white) }
        assertTrue("iOS's band less the film, in 8 bits, would not hold 5.2", ratio(title, band(q(exact), doubleArrayOf(1.0, 1.0, 1.0))) < 5.2)
        assertTrue("uncapped it fails (Task 0's wash)", ratio(title, band(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, 1.0, 1.0))) < 4.5)
        val none = androidx.compose.ui.graphics.Color.White
        assertEquals(none, BarDecisions.navBandCap(AppTheme.AURORA, dark = false, blur = true))
        assertEquals(none, BarDecisions.navBandCap(AppTheme.AURORA, dark = true, blur = false))
        assertEquals(none, BarDecisions.navBandCap(AppTheme.NORSE, dark = true, blur = true))
        assertEquals(none, BarDecisions.navBandCap(AppTheme.VARDR, dark = true, blur = true))
        // The frame passes the decision; the pane draws the cap as a Darken over the blurred page, before its film; the
        // page's own blur layer is no longer dimmed.
        val frame = source().substringAfter("private fun BarFrame(").substringBefore("\n}\n")
        assertTrue("the frame passes it", "cap = BarDecisions.navBandCap(theme, dark, blur)," in frame)
        val backdrop = java.io.File("src/main/java/com/vardrlabs/skipti/Backdrop.kt").readText()
        val glass = backdrop.substringAfter("fun Modifier.backdropGlass(").substringBefore("\n}\n")
        val darken = "if (blurred && cap != Color.White) translate(r.left, r.top) { drawOutline(outline, cap.copy(alpha = a), blendMode = BlendMode.Darken) }"
        assertTrue("a Darken cap, at the ground's alpha", darken in glass)
        assertTrue("after the blurred page", glass.indexOf("if (blurred) {") in 0 until glass.indexOf(darken))
        assertTrue("before the film", glass.indexOf(darken) < glass.indexOf("val f = if (blurred) film else filmUnblurred"))
        val record = backdrop.substringAfter("internal fun recordBlur(").substringBefore("\n    }\n")
        assertFalse("the page's blur layer is drawn as it is", "BlendMode" in record || "dim" in record)
    }

    /** D-220 (L-D217-6): the currency list is drawn as a pushed page: its window under the system bars with no dim,
     *  entering from the end (none under remove animations), the search in its own card row with Lucide's glyph, the
     *  rows in cards, the code at iOS's numeric 17 at 400, the check Lucide's. */
    @Test
    fun the_currency_list_is_a_pushed_page() {
        val c = screen("CurrencyChoice.kt")
        val list = c.substringAfter("fun CurrencyListDialog(")
        // D-225 (Design 10): the window is C2's, extracted to ListRows.kt as PushedWindow (ThemeArtTest proves the
        // currency list and the choice page both call it).
        val window = screen("ListRows.kt").substringAfter("fun PushedWindow(")
        assertTrue(window.contains("decorFitsSystemWindows = false"))
        assertTrue(window.contains("setDimAmount(0f)"))
        assertTrue("slides in unless reduce", window.contains("if (motion.reduce) EnterTransition.None else slideInHorizontally("))
        assertTrue("from the motion state", window.contains("val motion = rememberMotionState()"))
        assertTrue("iOS's push, 250 ms", window.contains("tween(250)"))
        assertTrue("rows in cards", list.contains("glassCard("))
        assertTrue("the page's backdrop", window.contains("backdropSource(backdrop)"))
        assertTrue(c.contains("Type.numeric(17, FontWeight.Normal"))
        assertTrue(c.contains("TabIcons.Search"))
        assertTrue(c.contains("TabIcons.Check"))
        assertFalse(c.contains("\"\u2713\""))
        assertFalse(c.contains("OutlinedTextField"))
    }

    /** D-220 fix wave (I8, F3): the list filters once per query and keys its rows by code; Common shows only for an
     *  empty query, as iOS's `query.isEmpty` (a space is a query). */
    @Test
    fun the_currency_list_filters_once_and_keys_its_rows() {
        val c = screen("CurrencyChoice.kt")
        assertTrue("filtered per query", Regex("""val filtered = remember\(query\) \{\s*DefaultCurrency\.all\.filter""").containsMatchIn(c))
        val card = c.substringAfter("private fun CurrencyCard(").substringBefore("\n}\n")
        assertTrue("rows keyed by code", Regex("""key\(code\) \{\s*if \(i > 0\) HorizontalDivider""").containsMatchIn(card))
        val page = c.substringAfter("private fun CurrencyListBody(").substringBefore("\n}\n")
        assertTrue("Common on an empty query", "if (query.isEmpty()) {\n        SectionLabel(CurrencyCopy.COMMON" in page)
        assertFalse("not on a blank one", "query.isBlank()" in page)
        // iOS's twin condition, read from the Swift file.
        assertTrue("iOS shows Common on isEmpty", "query.isEmpty" in swift("CurrencyChoice.swift"))
    }

    private fun swift(name: String): String = java.io.File("../../ios/Skipti/App/$name").readText()

    /** D-217 final fix (I5): iOS's bar buttons (Cancel, Done, Close) carry `.barTint()`, the ground's accent
     *  (`BarTint.token`); the sheet pills take the same token (it differs from the accent in Aurora light). */
    @Test
    fun the_sheet_pills_take_the_bar_tint() {
        val art = swift("ThemeArt.swift")
        assertTrue(art.contains("static let token = \"groundAccent\""))
        assertTrue(art.contains("func barTint() -> some View { tint(Tokens.groundAccent) }"))
        val pill = source().substringAfter("private fun BarPill(").substringBefore("\n}\n")
        assertTrue(pill.contains("Text(label, style = Type.body(dark).copy(fontSize = BarDecisions.BAR_BUTTON_SIZE.sp, color = Tok.groundAccent(dark)), maxLines = 1)"))
        assertFalse(pill.contains("Tok.accent(dark)"))
    }

    /** D-217 final fix (item 11): iOS 26 and 27 draw the back chevron inside its glass button in the label colour, the
     *  bar title's own (`TitleStyle`: the text token), not the tint (ThemeArt.swift's BarTint note, measured). Android's
     *  round back button draws its chevron in the twin token. */
    @Test
    fun the_back_chevron_takes_the_label_colour_as_on_ios_26() {
        val art = swift("ThemeArt.swift")
        assertTrue(art.contains("iOS 26 and later draw the chevron in the label colour in its glass, measured"))
        assertTrue(art.contains(".foregroundColor: Tokens.uiColor(\"text\")]"))
        val back = source().substringAfter("private fun BackButton(").substringBefore("\n}\n")
        assertTrue(back.contains("Icon(TabIcons.ChevronLeft, contentDescription = null, tint = Tok.text(dark), modifier = Modifier.size(BarDecisions.backChevronDp().dp))"))
        assertFalse(back.contains("Tok.accent(dark)"))
        // The title and the chevron are one colour, the bar's label colour.
        val title = java.io.File("src/main/java/com/vardrlabs/skipti/Tokens.kt").readText()
            .substringAfter("fun title(dark: Boolean, size: Float) = TextStyle(").substringBefore("\n    )")
        assertTrue(title.contains("color = Tok.text(dark),"))
    }

    /** D-219 fix wave (look defect 1): the back chevron is the size iOS 17 to 25 draws (18.0 x 10.5 pt at stroke 2.4), not the
     *  22 dp icon box's 13.2 x 7.7. The scale is iOS's constant, read from ThemeArt.swift; the 48 dp target does not move. */
    @Test
    fun the_back_chevron_is_the_size_ios_draws() {
        val ios = Regex("""static let backChevronScale: CGFloat = ([0-9.]+)""").find(swift("ThemeArt.swift"))
        assertTrue("iOS backChevronScale parsed", ios != null)
        assertEquals(ios!!.groupValues[1].toFloat(), BarDecisions.BACK_CHEVRON_SCALE, 0f)
        // The ink: chevron-left spans x 9 to 15 and y 6 to 18 (12 units tall, 6 wide) plus the stroke, at dp per unit.
        val unitsDp = BarDecisions.backChevronDp() / 24f
        assertEquals(1.25f, unitsDp, 1e-4f)
        assertEquals(18.0f, (12f + LucideStroke.WIDTH) * unitsDp, 0.01f)
        assertEquals(10.5f, (6f + LucideStroke.WIDTH) * unitsDp, 0.01f)
        assertEquals(30f, BarDecisions.backChevronDp(), 0f)
        // It sits inside the circle and the target is where it was.
        assertTrue(BarDecisions.backChevronDp() < BarDecisions.BACK_BUTTON_SIZE)
        assertEquals(48, BarDecisions.TOUCH_TARGET)
        val back = source().substringAfter("private fun BackButton(").substringBefore("\n}\n")
        assertTrue("the icon takes the decided size", "modifier = Modifier.size(BarDecisions.backChevronDp().dp)" in back)
        assertFalse("no literal 22", "size(22.dp)" in back)
        assertTrue("the touch target is the constant", ".size(BarDecisions.TOUCH_TARGET.dp)" in back)
    }

    // ---- D-217 Task 6: the four tab roots, the group page and expense detail on the bar -------------------------

    private fun screen(name: String): String = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    private val tabRoots = listOf(
        "OverviewTab.kt" to "title = \"Balance\"",
        "GroupsTab.kt" to "title = \"Groups\"",
        "ExpenseListScreen.kt" to "title = \"Expenses\"",
        "SettleAll.kt" to "title = SettleAllCopy.TITLE",
    )

    /** Each tab root draws its large title through the scaffold alone (with the Norse ornament under it): no
     *  `Type.title(` and no `TitleOrnament(` of its own in the file. */
    @Test
    fun the_four_tab_roots_draw_their_title_only_through_the_scaffold() {
        for ((file, title) in tabRoots) {
            val text = screen(file)
            assertFalse("$file draws a title of its own", text.contains("Type.title("))
            assertFalse("$file draws an ornament of its own", text.contains("TitleOrnament("))
            assertEquals("$file: one scaffold", 1, text.split("TabRootScaffold(").size - 1)
            val call = text.substringAfter("TabRootScaffold(").substringBefore(") {")
            assertTrue("$file: $title in $call", call.contains(title))
            assertTrue("$file: ornament = true", call.contains("ornament = true"))
        }
        // The scaffold's own ornament is `above` the shared content spacing, with no number a root can get wrong.
        val bar = source()
        assertTrue(bar.contains("Arrangement.spacedBy(BarDecisions.CONTENT_SPACING.dp)"))
        assertEquals(14, BarDecisions.CONTENT_SPACING)
    }

    /** The account glyph was a 48 dp row of its own above the tab content; it is now each root's trailing bar item. */
    @Test
    fun the_account_row_is_gone_and_the_glyph_is_every_roots_trailing_item() {
        val shell = screen("AppRoot.kt")
        assertFalse("the AccountBar row", shell.contains("AccountBar"))
        assertTrue("the glyph", shell.contains("internal fun AccountGlyph("))
        assertTrue("the slot", shell.contains("internal fun accountGlyph("))
        // Its tap target and spoken name are the ones the row had.
        assertTrue(shell.contains("contentDescription = \"Settings\""))
        // D-225 follow-up (M1): through openYou, a fresh You entry (YouTest pins what openYou does).
        assertEquals("the shell opens the account page for every root", 4, shell.split("onOpenAccount = ::openYou").size - 1)
        for ((file, _) in tabRoots) {
            val text = screen(file)
            assertTrue("$file takes onOpenAccount", text.contains("onOpenAccount: (() -> Unit)? = null"))
            val call = text.substringAfter("TabRootScaffold(").substringBefore(") {")
            assertTrue("$file: the glyph is the trailing item", call.contains("trailing = accountGlyph(dark, onOpenAccount)"))
        }
    }

    /** D-217 final fix (I4): HomeShell composes only the shown tab root, so a glyph that read the vault itself started
     *  plain on every tab switch and swapped to the initials or picture when the read returned. The shell reads them
     *  once (it outlives a switch, and the `key(generation)` remount) and hands them down; the glyph only draws. */
    @Test
    fun the_account_glyph_is_read_once_by_the_shell_and_drawn_by_each_root() {
        val shell = screen("AppRoot.kt")
        val glyph = shell.substringAfter("internal fun AccountGlyph(").substringBefore("\n}\n")
        assertTrue(shell.contains("internal fun AccountGlyph(dark: Boolean, onOpenAccount: () -> Unit) {"))
        assertTrue("the glyph reads the shell's face", glyph.contains("val face = LocalAccountFace.current"))
        for (banned in listOf("LaunchedEffect", "LedgerStore", "mutableStateOf", "loadSelf", "loadAvatar")) {
            assertFalse("the glyph does its own $banned", glyph.contains(banned))
        }
        val home = shell.substringAfter("private fun HomeShell(")
        val load = home.indexOf("LaunchedEffect(vaultPath, ProfileState.revision) {")
        val provide = home.indexOf("LocalAccountFace provides AccountFace(glyphAvatar, glyphName)")
        val remount = home.indexOf("key(generation) {")
        assertTrue("the shell loads the face", load > 0)
        assertTrue("and provides it outside the tab roots' remount", provide in (load + 1) until remount)
        assertEquals("one load", 1, shell.split("LaunchedEffect(vaultPath, ProfileState.revision) {").size - 1)
    }

    /** Expenses keeps its title (and ornament) while loading, on an error and when empty: those states are inside the
     *  scaffold, and the old padded Box that held them is gone. */
    @Test
    fun the_expenses_tab_keeps_its_title_in_every_state() {
        val text = screen("ExpenseListScreen.kt")
        val scaffold = text.indexOf("TabRootScaffold(")
        val states = listOf("loadError != null ->", "expenses == null ->", "expenses.isEmpty() ->", "else -> {")
            .map { text.indexOf(it, scaffold) }
        assertTrue("scaffold first", scaffold > 0)
        assertEquals("all four states follow the scaffold, in order", states.sorted(), states)
        assertTrue("every state found", states.all { it > scaffold })
        assertFalse("the old padded Box", text.contains("Box(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp))"))
    }

    /** The group page and expense detail are pushed pages: PushedBar, the group's name and the note as the title. */
    @Test
    fun the_group_page_and_expense_detail_use_the_pushed_bar() {
        val group = screen("OverviewTab.kt").substringAfter("fun GroupPage(")
        assertTrue(group.contains("PushedBar(title = named?.group?.name ?: \"\", onBack = onBack, scroll = scroll, backdrop = backdrop)"))
        assertFalse("a Back word", group.contains("Text(\"Back\""))
        assertFalse("a Back TextButton", group.contains("TextButton(onClick = onBack)"))
        val detail = screen("ExpenseDetailScreen.kt")
        assertTrue(detail.contains("PushedBar(title = ExpenseDetailUi.title(expense?.note), onBack = onBack, scroll = scroll, backdrop = backdrop)"))
        assertFalse("a Back word", detail.contains("Text(\"Back\""))
        assertFalse("a Back TextButton", detail.contains("TextButton(onClick = onBack)"))
        // The content starts under the bar, at the bar's own padding constant.
        // D-217 look fix 1: iOS's ScrollView at padding 16, not the tab root's constant.
        assertTrue(group.contains("top = BarDecisions.scrollTop(16f).dp"))
        assertTrue(detail.contains("top = BarDecisions.scrollTop(16f).dp"))
    }

    /** D-217 look fix 4: iOS pins Delete and Edit under the page (`formActionBar`), only for an expense that loads and is
     *  not in a read-only copy, the footer sentence staying in the scrolling content above them. Android pins them the
     *  way Add expense's action is pinned: a FormActionBar under the scrolling box, not a Row inside it. */
    @Test
    fun the_expense_details_delete_and_edit_are_pinned_under_the_page() {
        val ios = swift("ExpenseDetailView.swift")
        assertTrue(ios.contains("ScrollView { content(expense) }\n                    .formActionBar { actions(expense) }"))
        assertTrue("a copy has no actions", ios.contains("if let expense, isCopy(expense) {\n                // D-210: in a read-only copy it is only read; nothing to edit or delete.\n                ScrollView { content(expense) }\n            }"))
        assertTrue(ios.contains("micro(String(localized: \"Recorded in your ledger. It does not move money.\"), ground: true)"))
        val detail = screen("ExpenseDetailScreen.kt").substringBefore("if (confirmingDelete) {")
        assertTrue(detail.contains("val pinned = expense != null && !isCopy"))
        assertTrue(detail.contains("bottom = if (pinned) 8.dp else 16.dp)"))
        assertTrue(detail.contains("Box(Modifier.weight(1f)) {"))
        assertTrue(detail.contains("        if (pinned) FormActionBar {\n            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {"))
        // The buttons are in the pinned bar, after the scrolling box: Delete first, then Edit, and nowhere inside the scroll.
        val scrolling = detail.substringBefore("        if (pinned) FormActionBar {")
        val pinnedBar = detail.substringAfter("        if (pinned) FormActionBar {")
        assertFalse("Delete inside the scroll", scrolling.contains("SecondaryAction(\"Delete\""))
        assertFalse("Edit inside the scroll", scrolling.contains("label = \"Edit\""))
        assertTrue(pinnedBar.indexOf("SecondaryAction(\"Delete\"") in 0 until pinnedBar.indexOf("label = \"Edit\""))
        // The footer sentence is the last line of the content, above the bar.
        assertTrue(scrolling.contains("Recorded in your ledger. It does not move money."))
        assertFalse(pinnedBar.contains("Recorded in your ledger"))
    }

    /** The detail's title is the note (iOS: `expense?.note ?? "Expense"`) and the kicker over the amount is the
     *  category, as iOS draws it, and absent when there is none (not the note). */
    @Test
    fun the_expense_details_title_is_the_note_and_its_kicker_the_category() {
        assertEquals("Dinner", ExpenseDetailUi.title("Dinner"))
        assertEquals("Expense", ExpenseDetailUi.title(null))
        val tree = uniffi.skipti_core.ffiCategoryTreeDefault()
        val leaf = tree.categories.first { it.parent != null }
        val parent = tree.categories.first { it.id == leaf.parent }
        assertEquals("${parent.name}${Separator.meta()}${leaf.name}", ExpenseDetailUi.kicker(tree, leaf.id))
        assertEquals(null, ExpenseDetailUi.kicker(tree, null))
        assertEquals(null, ExpenseDetailUi.kicker(tree, "no-such-category-id"))
        val detail = screen("ExpenseDetailScreen.kt")
        assertTrue(detail.contains("ExpenseDetailUi.kicker("))
        assertFalse("the note as a kicker", detail.contains("Type.kicker(e.note"))
    }

    /** The ornament hangs 8 dp under the title's BASELINE, as iOS measures it, wherever the title's own box ends. */
    @Test
    fun the_scaffold_hangs_the_ornament_under_the_titles_baseline() {
        assertEquals(-6, TitleOrnaments.topOffset(above = 14))
        assertEquals(-16, TitleOrnaments.topOffset(above = 14, belowBaseline = 10))
        assertEquals(-14, TitleOrnaments.topOffset(above = 14, belowBaseline = 8))
        val bar = source()
        assertTrue(bar.contains("onTextLayout"))
        assertTrue(bar.contains("TitleOrnament(dark, above = BarDecisions.CONTENT_SPACING, belowBaseline = "))
    }

    /** D-217 T6 fix: how tall a state that fills a tab root is: the viewport less the top padding, what sits above the
     *  content (the title and the ornament) and the bottom padding; unknown (-1) until both are measured. */
    @Test
    fun a_filling_state_takes_the_viewport_under_the_title() {
        // 2000 px viewport, 189 px top padding, content starting 130 px into the column, 21 px bottom padding.
        assertEquals(2000 - 189 - 130 - 21, BarDecisions.fillRemainingPx(2000, 189, 130, 21))
        assertEquals(-1, BarDecisions.fillRemainingPx(0, 189, 130, 21))
        assertEquals(-1, BarDecisions.fillRemainingPx(2000, 189, -1, 21))
        assertEquals("a short phone never goes negative", 0, BarDecisions.fillRemainingPx(300, 189, 130, 21))
        assertEquals(8, BarDecisions.CONTENT_BOTTOM_PAD)
        val bar = source()
        // The scaffold measures the viewport and where the content starts, hands the result down, and the modifier
        // turns it into a minimum height (hidden until measured).
        assertTrue(bar.contains("Box(Modifier.fillMaxSize().onSizeChanged { viewportPx = it.height })"))
        assertTrue(bar.contains("contentTop = it.positionInParent().y.roundToInt()"))
        assertTrue(bar.contains("LocalTabRootFillPx provides fillPx"))
        assertTrue(bar.contains("fun Modifier.fillRemainingHeight(): Modifier"))
        assertTrue(bar.contains(".heightIn(min = height)"))
        assertTrue(bar.contains("alpha = if (px >= 0) 1f else 0f"))
    }

    /** iOS centres Expenses' loading, error and empty states in the space under the title (ContentUnavailableView):
     *  each is a box that fills what is left and centres its content, not a fixed height. */
    @Test
    fun the_expenses_non_data_states_fill_the_space_and_centre() {
        val text = screen("ExpenseListScreen.kt")
        val scaffold = text.indexOf("TabRootScaffold(")
        val marks = listOf("loadError != null ->", "expenses == null ->", "expenses.isEmpty() ->", "else -> {").map { text.indexOf(it, scaffold) }
        assertTrue("scaffold and four states found", scaffold > 0 && marks.all { it > scaffold })
        var checked = 0
        for (i in 0 until 3) {
            val branch = text.substring(marks[i], marks[i + 1])
            assertTrue("state $i has a body", branch.length > 60)
            assertTrue("state $i fills the remaining height", branch.contains("Modifier.fillRemainingHeight()"))
            assertTrue("state $i centres its content", branch.contains("contentAlignment = Alignment.Center"))
            assertFalse("state $i has a fixed height", Regex("""\.height\(""").containsMatchIn(branch.substringBefore("Text(")))
            checked++
        }
        assertEquals(3, checked)
        assertFalse("the fixed 320 dp box", text.contains("STATE_HEIGHT"))
    }

    /** The expense detail's three kickers sit on the page ground: iOS draws them with `micro(..., ground: true)`, the
     *  ground token (D-214), the category, "Paid by" and the footer line alike. */
    @Test
    fun the_expense_details_ground_kickers_take_the_ground_token() {
        val lines = screen("ExpenseDetailScreen.kt").lines()
        val wanted = listOf("ExpenseDetailUi.kicker(", "\"Paid by \${name(e.expense.payer)}\"", "Recorded in your ledger")
        for (w in wanted) {
            val at = lines.indexOfFirst { it.contains(w) }
            assertTrue("$w found", at >= 0)
            // the kicker's Text may open on the line before the style
            val around = lines.subList(at, minOf(lines.size, at + 2)).joinToString("\n")
            assertTrue("$w on the ground token", around.contains("Tok.groundFaint(dark)"))
            assertFalse("$w not on textDim", around.contains("Tok.textDim(dark)"))
        }
    }

    /** The join page's invitation sentence and joined line are display 28 (iOS: `TypeRole.display(28)`), not 40. */
    @Test
    fun the_join_pages_body_sentences_are_display_28() {
        val lines = screen("Invites.kt").lines()
        var found = 0
        for (w in listOf("Text(InviteCopy.joined(", "Text(InviteCopy.invited(")) {
            val line = lines.firstOrNull { it.contains(w) }
            assertTrue("$w found", line != null)
            assertTrue("$w at 28 sp: $line", line!!.contains("Type.display(dark).copy(fontSize = 28.sp)"))
            found++
        }
        assertEquals(2, found)
    }
}
