package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.LocaleTag

/**
 * D-184: 0.3.0's tester reports. Goldens are worked by hand in
 * .drafts/d184/golden.py (en-IN grouping, independent of core). The twin of
 * iOS's BalanceLineTests D-184 tests.
 */
class TesterFixesTest {

    private fun id(n: Int) = "%032x".format(n)

    /** The tester's own numbers: an exact split of ₹30,000 where the four
     *  amounts came to ₹25,000. Core refuses it; the words say by how much,
     *  where the app used to show "expected=3000000, got=2500000". */
    @Test
    fun the_testers_split_is_refused_in_words_through_the_real_core() {
        val (a, b, c, d) = listOf(id(1), id(2), id(3), id(4))
        val draft = AddExpenseUi.SplitDraft.Exact(
            mapOf(a to "500", b to "4500", c to "10000", d to "10000"),
        )
        try {
            AddExpenseUi.buildExpense("30000", "INR", "", null, setOf(a, b, c, d), draft, a, id(9))
            fail("core accepted a split that does not add up")
        } catch (e: CoreException) {
            assertEquals(
                "The amounts add up to ₹25,000.00 of ₹30,000.00. ₹5,000.00 is still to assign.",
                SplitWords.message(e, "INR", LocaleTag.EN_IN),
            )
            // The live line says the same while the person is still typing.
            assertEquals(SplitWords.message(e, "INR", LocaleTag.EN_IN), SplitWords.live(e, "INR", LocaleTag.EN_IN))
        }
    }

    /** While an amount is still empty, the live line counts it as nothing:
     *  ₹15,000 typed of ₹30,000. Save still refuses the empty field. */
    @Test
    fun the_live_line_shows_while_amounts_are_still_being_typed() {
        val (a, b, c, d) = listOf(id(1), id(2), id(3), id(4))
        val people = setOf(a, b, c, d)
        val draft = AddExpenseUi.SplitDraft.Exact(mapOf(a to "500", b to "4500", c to "10000", d to ""))
        val live = runCatching {
            AddExpenseUi.buildExpense("30000", "INR", "", null, people, AddExpenseUi.liveDraft(draft, people), a, id(9))
        }.exceptionOrNull()
        assertEquals(
            "The amounts add up to ₹15,000.00 of ₹30,000.00. ₹15,000.00 is still to assign.",
            live?.let { SplitWords.live(it, "INR", LocaleTag.EN_IN) },
        )
        val saved = runCatching {
            AddExpenseUi.buildExpense("30000", "INR", "", null, people, draft, a, id(9))
        }.exceptionOrNull()
        assertTrue("Save still refuses the empty amount", saved is CoreException.InvalidInput)
    }

    @Test
    fun too_much_items_and_percentages_read_in_words() {
        assertEquals(
            "The amounts add up to ₹35,000.00, ₹5,000.00 more than the ₹30,000.00 spent.",
            SplitWords.message(CoreException.ExactSumMismatch(3_000_000, 3_500_000), "INR", LocaleTag.EN_IN),
        )
        assertEquals(
            "The items add up to ₹4,000.00 of ₹6,400.00. ₹2,400.00 is still to assign.",
            SplitWords.message(CoreException.TotalMismatch(640_000, 400_000), "INR", LocaleTag.EN_IN),
        )
        assertEquals(
            "The percentages add up to 90.5%. They need to make 100%.",
            SplitWords.message(CoreException.PercentMismatch(9_050uL), "INR", LocaleTag.EN_IN),
        )
        assertEquals(
            listOf("90%", "90.5%", "90.05%", "123.45%", "0%"),
            listOf(9_000uL, 9_050uL, 9_005uL, 12_345uL, 0uL).map { SplitWords.percent(it) },
        )
    }

    /** Negative: only a split that does not add up gets the live line;
     *  everything else waits for Save, and an error that is not about the
     *  split keeps its own words. */
    @Test
    fun only_a_split_that_does_not_add_up_is_live() {
        assertNull(SplitWords.live(CoreException.EmptyParticipants(), "INR", LocaleTag.EN_IN))
        assertNull(SplitWords.live(IllegalStateException("x"), "INR", LocaleTag.EN_IN))
        assertEquals(
            "Choose at least one person to split with.",
            SplitWords.message(CoreException.EmptyParticipants(), "INR", LocaleTag.EN_IN),
        )
        assertNull(SplitWords.message(CoreException.InvalidInput("x"), "INR", LocaleTag.EN_IN))
    }

    /** The window resizes for the keyboard. The default, pan, slid the whole
     *  window on every keystroke while the form scrolled too (a tester's
     *  "jarring" jump); Android's own resize plus the root's inset padding
     *  replaces it. */
    @Test
    fun the_keyboard_resizes_the_app_instead_of_sliding_it() {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(java.io.File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length)
            .map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ANDROID, "name") == ".MainActivity" }
        assertEquals("adjustResize", main.getAttributeNS(ANDROID, "windowSoftInputMode"))
        val root = java.io.File("src/main/java/com/vardrlabs/skipti/AppRoot.kt").readText()
        assertTrue("the root pads for bars and keyboard", root.contains(".windowInsetsPadding(WindowInsets.safeDrawing)"))
    }

    /** After an erase the app says it is done (a tester: "no confirmation at
     *  all"); the words twin iOS's EraseCopy. */
    @Test
    fun a_finished_erase_is_announced() {
        LedgerErase.notice = false
        val before = LedgerErase.generation
        LedgerErase.announce()
        assertTrue(LedgerErase.notice)
        assertEquals(before + 1, LedgerErase.generation)
        LedgerErase.notice = false
        assertFalse(LedgerErase.notice)
        assertEquals("This phone's ledger is erased", EraseCopy.DONE_TITLE)
        assertEquals(
            "Everything SKIPTI kept on this phone is gone. Your account, if you have one, is not deleted.",
            EraseCopy.DONE_BODY,
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
