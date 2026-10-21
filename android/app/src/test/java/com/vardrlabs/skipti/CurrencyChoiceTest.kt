package com.vardrlabs.skipti

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-165 (SPEC §5.2): the default currency and the full list; the twin of
 *  iOS's CurrencyChoiceTests. */
class CurrencyChoiceTest {
    private val known = setOf("INR", "USD", "EUR", "CHF")

    @Test
    fun a_stored_choice_wins_then_the_region_then_usd() {
        assertEquals("EUR", DefaultCurrency.resolve("EUR", "INR", known))
        assertEquals("CHF", DefaultCurrency.resolve(null, "CHF", known))
        assertEquals("USD", DefaultCurrency.resolve(null, null, known))
        assertEquals("INR", DefaultCurrency.resolve("", "INR", known))
        // Codes core does not know never stick, stored or regional.
        assertEquals("INR", DefaultCurrency.resolve("XCG", "INR", known))
        assertEquals("USD", DefaultCurrency.resolve("usd", "ZZZ", known))
        // The region comes from the locale's country; none, no currency.
        assertEquals("INR", DefaultCurrency.regionCurrency(Locale("en", "IN")))
        assertNull(DefaultCurrency.regionCurrency(Locale("en")))
    }

    /** U26 (the twin of testTheCurrencyIsSuggestedUntilOneIsChosen). */
    @Test
    fun the_currency_is_suggested_until_one_is_chosen() {
        assertNull("chosen: no suggestion", DefaultCurrency.suggestion("EUR", "INR", known))
        assertEquals("CHF", DefaultCurrency.suggestion(null, "CHF", known))
        assertEquals("USD", DefaultCurrency.suggestion(null, null, known))
        assertEquals("an unknown code is no choice", "INR", DefaultCurrency.suggestion("XCG", "INR", known))
        assertEquals("USD", DefaultCurrency.suggestion("", "ZZZ", known))
        assertEquals("Suggested", CurrencyCopy.SUGGESTED)
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val list = dir.resolve("CurrencyChoice.kt").readText()
        assertTrue("nothing ticked while suggesting", "if (suggested == null) selected else null, suggested" in list)
        assertTrue("the suggestion leads, above Common", "if (query.isEmpty() && suggested != null) {" in list)
        assertTrue("You's list suggests", "suggested = DefaultCurrency.suggestion()," in dir.resolve("AccountHubScreen.kt").readText())
    }

    @Test
    fun the_full_list_is_cores_sorted_and_holds_the_promoted_nine() {
        val all = DefaultCurrency.all
        assertEquals("counted independently from core's tables", 164, all.size)
        assertEquals(all.sorted(), all)
        assertEquals(all.size, all.toSet().size)
        for (code in AddExpenseUi.promotedCurrencies) assertTrue(code, code in all)
    }

    @Test
    fun the_short_list_is_the_promoted_nine_then_extras_once() {
        val nine = listOf("INR", "USD", "EUR", "GBP", "JPY", "AED", "SGD", "AUD", "CAD")
        assertEquals(nine, DefaultCurrency.choices(emptyList()))
        assertEquals("promoted codes are not repeated", nine, DefaultCurrency.choices(listOf("USD", "INR")))
        assertEquals(nine + listOf("CHF", "THB"), DefaultCurrency.choices(listOf("CHF", "THB", "CHF")))
    }

    @Test
    fun search_matches_code_or_name_ignoring_case() {
        assertTrue(CurrencyCopy.matches("CHF", "Swiss Franc", ""))
        assertTrue(CurrencyCopy.matches("CHF", "Swiss Franc", "chf"))
        assertTrue(CurrencyCopy.matches("CHF", "Swiss Franc", " swiss "))
        assertTrue("accents ignored", CurrencyCopy.matches("ISK", "Icelandic Króna", "krona"))
        assertFalse(CurrencyCopy.matches("CHF", "Swiss Franc", "euro"))
        assertFalse("no name: code only", CurrencyCopy.matches("PRB", null, "rouble"))
    }
}
