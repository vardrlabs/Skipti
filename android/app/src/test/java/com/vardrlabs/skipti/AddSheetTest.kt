package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.SplitSpecInput
import uniffi.skipti_core.ffiSplitCompute

/**
 * D-183: the Add sheet's live line is core's effect in words (the founder's
 * canvas: ₹2,400 lunch, four ways, you paid: "You’ll get back ₹1,800.00").
 * Worked by hand: ₹600.00 each; you paid, so the other three owe you
 * ₹1,800.00; Rahul paid, so you owe him ₹600.00; not in it, nothing. The twin
 * of iOS's testTheAddSheetSummaryIsCoresEffectInWords.
 */
class AddSheetTest {

    private fun id(n: Int) = "%032x".format(n)

    private fun expense(payer: String, shares: List<ShareOut>) =
        ExpenseInput(payer, 240_000, "INR", shares.map { IdAmount(it.id, it.minorUnits) }, null, null)

    @Test
    fun the_summary_is_cores_effect_in_words() {
        val (you, rahul, meera, sam) = listOf(id(1), id(2), id(3), id(4))
        val four = ffiSplitCompute(240_000, "INR", listOf(you, rahul, meera, sam), SplitSpecInput.Equal)
        assertEquals("You’ll get back ₹1,800.00", AddSheetCopy.summary(expense(you, four), you, LocaleTag.EN_IN))
        assertEquals("You’ll owe ₹600.00", AddSheetCopy.summary(expense(rahul, four), you, LocaleTag.EN_IN))
        val three = ffiSplitCompute(240_000, "INR", listOf(rahul, meera, sam), SplitSpecInput.Equal)
        assertEquals("Nothing changes for you", AddSheetCopy.summary(expense(rahul, three), you, LocaleTag.EN_IN))
    }

    @Test
    fun the_add_sheet_words_match_ios() {
        assertEquals("How much?", AddSheetCopy.HOW_MUCH)
        assertEquals("What was it for?", AddSheetCopy.WHAT_FOR)
        assertEquals("Ledger", AddSheetCopy.LEDGER)
        assertEquals("You’ll get back €32.00", AddSheetCopy.summary(3_200, "€32.00"))
        assertEquals("You’ll owe €32.00", AddSheetCopy.summary(-3_200, "€32.00"))
        assertEquals("Nothing changes for you", AddSheetCopy.summary(0, "€0.00"))
    }

    /** D-225 T7 (A6, the founder 2026-10-17): the split card's words and the hints, one table iOS's twin pins to the
     *  same lines; no dash in any hint, and the screen draws them from the table (no literal of its own). D-225 fix
     *  wave (M3, A8): all four hints are `AddSheetCopy` constants, and `AddSheetCopy.hint` pins each to its split kind,
     *  the same kind iOS's `AddSheetCopy.hint` gives it (read out of AddExpenseScreen.swift). */
    @Test
    fun the_split_words_and_hints_are_one_table() {
        assertEquals("Split", AddSheetCopy.SPLIT)
        assertEquals("Split type", AddSheetCopy.SPLIT_TYPE)
        assertEquals("Paid by", AddSheetCopy.PAID_BY)
        val want = mapOf(
            "Equal" to null,
            "Exact" to null,
            "Percentage" to "Percentages must add up to 100",
            "Shares" to "WEIGHTS, NOT AMOUNTS: 2 ADULTS + 1 CHILD AS 2:2:1",
            "Adjustment" to "Equal split, plus or minus each adjustment",
            "Itemised" to null,
            "Payer only" to "One person paid, nobody owes: logged in the group",
        )
        assertEquals("every kind", want.keys.toList(), AddExpenseUi.allKinds.map { it.label })
        for (kind in AddExpenseUi.allKinds) assertEquals("the hint for ${kind.label}", want[kind.label], AddSheetCopy.hint(kind))
        assertEquals(AddSheetCopy.PERCENTAGE_HINT, AddSheetCopy.hint(AddExpenseUi.SplitDraft.Percentage()))
        assertEquals(AddSheetCopy.SHARES_HINT, AddSheetCopy.hint(AddExpenseUi.SplitDraft.Shares()))
        assertEquals(AddSheetCopy.ADJUSTMENT_HINT, AddSheetCopy.hint(AddExpenseUi.SplitDraft.Adjustment()))
        assertEquals(AddSheetCopy.PAYER_ONLY_HINT, AddSheetCopy.hint(AddExpenseUi.SplitDraft.PayerOnly))
        for (hint in want.values.filterNotNull()) {
            assertFalse("no dash in $hint", '\u2014' in hint || '\u2013' in hint)  // em-dash-ok: the escape builds the dash this test asserts is absent
        }
        // iOS's table, kind by kind: `case .<kind>: return <name>` and `static let <name> = "<text>"`.
        val swift = java.io.File("../../ios/Skipti/App/AddExpenseScreen.swift").readText()
        val table = swift.substringAfter("static func hint(_ kind: AddExpenseScreen.SplitKind) -> String? {").substringBefore("\n    }\n")
        val iosKind = mapOf("Equal" to "equal", "Exact" to "exact", "Percentage" to "percentage", "Shares" to "shares",
            "Adjustment" to "adjustment", "Itemised" to "itemised", "Payer only" to "payerOnly")
        for ((label, kind) in iosKind) {
            val named = Regex("""case [^:\n]*\.$kind\b[^:\n]*: return (\w+)""").find(table)!!.groupValues[1]
            val text = if (named == "nil") null else Regex("""static let $named = "([^"]*)"""").find(swift)!!.groupValues[1]
            assertEquals("iOS's hint for $label", want[label], text)
        }
        val screen = java.io.File("src/main/java/com/vardrlabs/skipti/AddExpenseScreen.kt").readText()
        val extras = screen.substringAfter("private fun SplitKindExtras(").substringBefore("\n}\n")
        assertTrue("the hint from the table", "AddSheetCopy.hint(draft)?.let { Text(Type.kicker(it), style = Type.microLabel(dark)) }" in extras)
        for (hint in want.values.filterNotNull()) assertFalse("no literal hint on the screen: $hint", "\"$hint\"" in extras)
        assertFalse("no old dashed copy", "AMOUNTS \u2014" in screen || "owes \u2014" in screen)  // em-dash-ok: the escape builds the dash this test asserts is absent
        // M4: the summary and problem lines at the prose size, from the scale.
        assertEquals("prose, not a literal", 2, Regex("""fontSize = TypeScale\.prose\.sp\),\n\s+textAlign = TextAlign\.Center""").findAll(screen).count())
        assertFalse("no literal 15", "fontSize = 15.sp" in screen)
    }
}
