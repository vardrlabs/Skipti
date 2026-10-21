package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-234, the twin-fix unit: the Android half of the looks' findings that have no test file of their own. Where the two
 * phones share words, the iOS source is read and compared, so the twins cannot drift.
 */
class TwinFixTest {
    private fun main(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
    private fun swift(name: String) = java.io.File("../../ios/Skipti/App/$name").readText()

    /** L1 (the D-228 look's O1): `--import-proof` on a vault that will not open says so on its screen and never crashes
     *  the app; the vault leg stays false (untouched is unproven), the other legs still run. */
    @Test
    fun the_import_proof_says_a_vault_that_will_not_open() {
        JvmDates.install() // the review formats dates; the JVM has no ICU (as ImportFlowTest)
        val store = LedgerStore("/nonexistent/ledger-vault.db") { throw uniffi.skipti_core.StoreException.WrongKey() }
        val r = ImportProof.prove(store)
        assertTrue("the plans still prove", r.splitwisePlanned && r.budgetBakersPlanned && r.exclusionFiltered)
        assertFalse("untouched is unproven", r.atomicRefusalAndVaultUntouched)
        assertEquals("WrongKey", r.vaultError)
        assertEquals("the vault would not open: WrongKey", r.detail)
        assertFalse(r.passed)
        assertNull("no store, no vault error", ImportProof.prove(null).vaultError)
    }

    /** L7 (the D-229 look's F2, decision 87): the empty lines say why, word for word iOS's. */
    @Test
    fun the_empty_lines_say_why_and_match_ios() {
        // D-249 (U10): Add expense's two "belong to a group" lines went (Just me takes the expense); gone on both phones.
        for (gone in listOf("Expenses belong to a group", "You can't add to your groups from this phone")) {
            assertFalse("AddExpenseScreen.swift still says $gone", gone in swift("AddExpenseScreen.swift"))
            assertFalse("AddExpenseScreen.kt still says $gone", gone in main("AddExpenseScreen.kt"))
        }
        assertEquals("Nothing to settle yet. Add an expense with the + below.", SettleAllCopy.empty(0))
        assertEquals("Nothing to settle from this phone. Your groups here can't take new records.", SettleAllCopy.empty(1))
        assertEquals(SettleAllCopy.NOTHING_HERE, SettleAllCopy.empty(3))
        assertTrue("static let nothingHere = \"${SettleAllCopy.NOTHING_HERE}\"" in swift("SettleAll.swift"))
        assertTrue("Settle up uses it", "Text(SettleAllCopy.empty(groupCount), style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark))" in main("SettleAll.kt"))
    }

    /** L13 (the D-232 look): Home draws nothing under its title until its first read (iOS reads before its first frame),
     *  so a fresh install never flashes the date and greeting before its empty state. */
    @Test
    fun home_draws_nothing_under_its_title_before_the_first_read() {
        val home = main("OverviewTab.kt")
        val arms = home.substringAfter("        when {\n            needsName -> EmptyHome(").substringBefore("            else -> {")
        assertTrue("an arm for the read in flight", "            !loaded -> Unit\n" in arms)
        // D-249: no empty-state arm after it (with no group yet the hero asks, in the page's own stack).
        assertFalse("no empty state arm", "loaded && groups.isEmpty() && error == null ->" in arms)
    }

    /** L14 (the D-232 lock look, decision 88): phones run portrait, as the iPhone does (its Info.plist allows portrait
     *  only); Android 16 lets large screens rotate whatever the manifest says. */
    @Test
    fun phones_run_portrait_as_the_iphone_does() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val activity = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        assertTrue(activity, "android:screenOrientation=\"portrait\"" in activity)
        val plist = java.io.File("../../ios/Skipti/Info.plist").readText()
        val orientations = plist.substringAfter("<key>UISupportedInterfaceOrientations</key>").substringBefore("</array>")
        assertTrue("UIInterfaceOrientationPortrait" in orientations)
        assertFalse("the iPhone never turns", "Landscape" in orientations)
    }

    /** L4 (the D-228 look's O4, decision 92): a large-title page rests as UIKit's does: a scroll that stops with the title
     *  half under the bar goes back to the top when less than half way, on to the fold (the title fully under, the
     *  bar's own title showing) otherwise; and a page whose content cannot scroll its title fully under gets the room it
     *  lacks (UIKit's collapsing bar frees that height). */
    @Test
    fun a_large_title_page_never_rests_half_folded() {
        assertEquals("at the top", 0, BarDecisions.largeTitleRest(scrollPx = 0, foldPx = 56))
        assertEquals("a little: back to the top", 0, BarDecisions.largeTitleRest(scrollPx = 20, foldPx = 56))
        assertEquals("half way: on to the fold", 56, BarDecisions.largeTitleRest(scrollPx = 28, foldPx = 56))
        assertEquals("most of the way: on to the fold", 56, BarDecisions.largeTitleRest(scrollPx = 55, foldPx = 56))
        assertEquals("folded: stays", 56, BarDecisions.largeTitleRest(scrollPx = 56, foldPx = 56))
        assertEquals("past the fold: stays", 300, BarDecisions.largeTitleRest(scrollPx = 300, foldPx = 56))
        assertEquals("no title measured yet: stays", 20, BarDecisions.largeTitleRest(scrollPx = 20, foldPx = 0))
        assertEquals("content that does not scroll: no room added", 0, BarDecisions.largeTitleSlackPx(maxScrollPx = 0, foldPx = 56))
        assertEquals("a short form: the room it lacks", 16, BarDecisions.largeTitleSlackPx(maxScrollPx = 40, foldPx = 56))
        assertEquals("a long form: none", 0, BarDecisions.largeTitleSlackPx(maxScrollPx = 56, foldPx = 56))
        assertEquals("not measured yet", 0, BarDecisions.largeTitleSlackPx(maxScrollPx = Int.MAX_VALUE, foldPx = 56))
        val page = main("SkiptiBar.kt").substringAfter("fun SheetPage(").substringBefore("\n}\n")
        for (needle in listOf(
            "val foldPx = if (largeTitle && titleBottom != Int.MAX_VALUE) titleBottom - barPx else 0",
            // Keyed on the scroll state, not on isScrollInProgress: the rest's own scroll sets that, and a key change
            // would cancel the rest mid-way.
            "LaunchedEffect(scroll, foldPx, reduce) {",
            "launch { scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }",
            "if (!moving && dragged) {",
            "val rest = BarDecisions.largeTitleRest(scroll.value, foldPx)",
            "if (reduce) scroll.scrollTo(rest) else scroll.animateScrollTo(rest)",
            // The slack is measured in layout from the content alone (a slack read back from scroll.maxValue, which
            // already holds the last frame's slack, would chase itself frame after frame).
            ".onSizeChanged { viewportPx = it.height }",
            "val slack = BarDecisions.largeTitleSlackPx(content.height - viewportPx, foldPx)",
            "layout(content.width, content.height + slack) { content.place(0, 0) }",
        )) {
            assertTrue("SheetPage: $needle", needle in page)
        }
    }

    /** L10 (the D-231 look, decision 95): the settle celebration's words sit on the theme's card, 24 in from the sides,
     *  as on iOS (whose card is `.frame(maxWidth: .infinity)`: the full width less the 24s, not the words' width). */
    @Test
    fun the_celebrations_words_sit_on_a_card() {
        val settled = main("Settled.kt")
        assertTrue(".clearAndSetSemantics {}\n                        // D-234 (L10, decision 95): on the theme's card, so the words never read on the page's own words.\n                        .glassCard(dark)," in settled)
        assertTrue("as wide as iOS's", "Modifier\n                        .fillMaxWidth()\n                        .padding(horizontal = 24.dp)\n                        .graphicsLayer {\n                            val m = CelebrationTimeline.message(" in settled)
        assertTrue("iOS's twin", ".glassCard()\n                    .padding(.horizontal, 24)" in swift("Settled.swift"))
        assertTrue("iOS's width", ".frame(maxWidth: .infinity)\n                    .foregroundStyle(Tokens.text)\n                    .multilineTextAlignment(.center)\n                    .glassCard()" in swift("Settled.swift"))
    }

    /** L12-d (the D-231 re-look): the expense rows' card pads 14, iOS's, and iOS keeps Android's 8 between the words and
     *  the effect. */
    @Test
    fun the_expense_rows_match_ios() {
        assertTrue("Column(Modifier.glassCard(dark, padding = 14)) {" in main("ExpenseListScreen.kt"))
        val ios = swift("ExpenseListView.swift")
        assertTrue(".glassCard(padding: 14)" in ios)
        assertTrue("return HStack(alignment: .top, spacing: 0) {" in ios)
        assertTrue("Spacer(Modifier.width(8.dp))" in main("ExpenseListScreen.kt"))
    }

    /** L12-O1 (the D-231 re-look, decision 97): the raised + draws no press film (Material's ripple faded in even under
     *  Less motion; iOS's + has none): its press is the scale and the turn, both behind the motion door. */
    @Test
    fun the_plus_draws_no_press_film() {
        val art = main("ThemeArt.kt")
        val face = art.substring(art.indexOf("fun AddButtonFace("), art.indexOf("\n}\n", art.indexOf("fun AddButtonFace(")))
        assertTrue("indication = null," in face)
        assertFalse("ripple(" in face)
    }
}
