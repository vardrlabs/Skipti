package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.ProjectedExpenseOut

/** D-181: the twin of iOS's testExpensesAreGroupedByGroupNewestFirst. */
class ExpenseSectionsTest {

    private fun e(id: String, group: String?, ts: Long) = ProjectedExpenseOut(
        id = id,
        expense = ExpenseInput("0".repeat(31) + "1", 100, "INR", emptyList(), group, null),
        note = null, category = null, timestampMs = ts.toULong(), addedBy = null, addedAtMs = ts.toULong(),
        deleted = false, lastEditedAtMs = null, lastEditedBy = null, seen = emptyList(), moneyConflict = emptyList(),
    )

    @Test
    fun expenses_are_grouped_by_group_newest_first() {
        val goa = "a".repeat(32)
        val flat = "b".repeat(32)
        val all = listOf(e("1", goa, 10), e("2", flat, 30), e("3", null, 99), e("4", goa, 20))
        val sections = ExpenseSections.build(all, null)
        assertEquals(listOf(flat, goa, null), sections.map { it.groupId })
        assertEquals(listOf("4", "1"), sections[1].expenses.map { it.id })
        assertEquals(listOf(goa), ExpenseSections.build(all, goa).map { it.groupId })
        assertEquals("you owe ₹1,200.00", ExpenseCopy.effect(-120_000, "₹1,200.00"))
        assertEquals("you lent ₹9,000.00", ExpenseCopy.effect(900_000, "₹9,000.00"))
        assertEquals("not involved", ExpenseCopy.effect(0, "₹0.00"))
    }
}
