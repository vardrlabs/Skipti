package com.vardrlabs.skipti

import com.vardrlabs.skipti.AddExpenseUi.SplitDraft
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.ConversionInput
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.ExpenseRevisionIn
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * D-143: what Edit, Delete, Undo and SPEC §6.1's amount question write
 * (`ExpenseEditingUi`), and the form an edit opens in: the twin of iOS
 * ExpenseEditingTests (D-142), goldens written by hand the same way (digits
 * from ISO 4217's exponents; the prompt from SPEC §6.1's sentence and core's
 * en-IN grouping). In the shared test set: both flavours run it.
 */
class ExpenseEditingTest {

    private val me = "0".repeat(31) + "1"
    private val rahul = "0".repeat(31) + "2"
    private val group = "0".repeat(31) + "9"
    private val dinnerId = "1000:0:" + "0".repeat(31) + "1"
    private val editA = "1100:0:" + "0".repeat(31) + "1"
    private val editB = "1200:0:" + "0".repeat(31) + "2"

    private fun money(
        paid: Long,
        shares: List<Pair<String, Long>>,
        payer: String = me,
        conversion: ConversionInput? = null,
    ) = ExpenseInput(
        payer = payer, paidMinor = paid, currencyCode = "INR",
        shares = shares.map { IdAmount(id = it.first, amountMinor = it.second) },
        group = group, conversion = conversion,
    )

    private fun projected(
        m: ExpenseInput,
        note: String? = "Dinner",
        seen: List<String> = emptyList(),
        conflict: List<ExpenseInput> = emptyList(),
    ) = ProjectedExpenseOut(
        id = dinnerId, expense = m, note = note, category = null, timestampMs = 1_000uL,
        addedBy = me, addedAtMs = 1_000uL, deleted = false, lastEditedAtMs = null,
        lastEditedBy = null, seen = seen, moneyConflict = conflict,
    )

    // The form an edit opens in

    @Test
    fun an_equal_split_opens_as_equal_with_the_stored_amount_as_digits() {
        val form = ExpenseEditingUi.prefill(projected(money(90_000, listOf(me to 45_000L, rahul to 45_000L))))
        assertEquals(SplitDraft.Equal, form.draft)
        assertEquals("900.00", form.amountText)
        assertEquals("INR", form.currency)
        assertEquals("Dinner", form.note)
        assertEquals(me, form.payer)
        assertEquals(setOf(me, rahul), form.participants)
    }

    @Test
    fun an_uneven_split_opens_as_exact_to_the_minor_unit() {
        val form = ExpenseEditingUi.prefill(projected(money(90_005, listOf(me to 30_000L, rahul to 60_005L))))
        assertEquals("900.05", form.amountText)
        assertEquals(SplitDraft.Exact(mapOf(me to "300.00", rahul to "600.05")), form.draft)
    }

    @Test
    fun a_payer_only_expense_opens_as_payer_only() {
        val form = ExpenseEditingUi.prefill(projected(money(90_000, listOf(rahul to 90_000L), payer = rahul)))
        assertEquals(SplitDraft.PayerOnly, form.draft)
        assertEquals(rahul, form.payer)
    }

    // What an edit writes

    @Test
    fun an_untouched_edit_writes_nothing_even_with_the_shares_reordered() {
        val p = projected(money(90_000, listOf(me to 45_000L, rahul to 45_000L)), seen = listOf(editA))
        val same = money(90_000, listOf(rahul to 45_000L, me to 45_000L))
        assertNull(ExpenseEditingUi.revision(p, same, "Dinner", null, me))
    }

    @Test
    fun a_note_edit_carries_only_the_note_and_a_cleared_note_is_written() {
        val m = money(90_000, listOf(me to 45_000L, rahul to 45_000L))
        val p = projected(m, seen = listOf(editA))
        assertEquals(
            ExpenseRevisionIn(
                target = dinnerId, seen = listOf(editA), editor = rahul, money = null, note = "Taxi",
                category = null, deleted = null,
            ),
            ExpenseEditingUi.revision(p, m, "Taxi", null, rahul),
        )
        val cleared = ExpenseEditingUi.revision(p, m, "", null, rahul)!!
        assertEquals("a removed note is written as \"\", or the old one survives", "", cleared.note)
        assertNull(cleared.money)
    }

    @Test
    fun a_new_amount_keeps_the_group_and_leaves_the_old_conversion_behind() {
        val rate = ConversionInput(
            convertedMinor = 1_080, convertedCurrency = "USD", rateNum = 12, rateDen = 1_000,
            rateTimestampMs = 500uL,
        )
        val p = projected(money(90_000, listOf(me to 45_000L, rahul to 45_000L), conversion = rate))
        val moved = money(120_000, listOf(me to 60_000L, rahul to 60_000L)).copy(group = null)
        val edit = ExpenseEditingUi.revision(p, moved, "Dinner", null, me)!!
        assertEquals("an edit never moves an expense between groups", group, edit.money?.group)
        assertNull("the frozen conversion described the old amount", edit.money?.conversion)
        assertNull(edit.note)

        val resplit = ExpenseEditingUi.revision(
            p, money(90_000, listOf(me to 30_000L, rahul to 60_000L)), "Dinner", null, me,
        )!!
        assertEquals("same amount, same currency: the rate still holds", rate, resplit.money?.conversion)
    }

    // SPEC §6.1's question

    @Test
    fun the_conflict_prompt_is_spec_six_ones_sentence() {
        val a = money(120_000, listOf(me to 60_000L, rahul to 60_000L))
        val b = money(125_000, listOf(me to 62_500L, rahul to 62_500L))
        val p = projected(b, seen = listOf(editA, editB), conflict = listOf(a, b))
        assertEquals(
            "Two devices set different amounts. Keep ₹1,200.00 or ₹1,250.00?",
            ExpenseEditingUi.conflictPrompt(p, LocaleTag.EN_IN),
        )
        assertEquals("Keep ₹1,200.00", ExpenseEditingUi.keepLabel(a, p, LocaleTag.EN_IN))
        assertNull(ExpenseEditingUi.conflictPrompt(projected(b), LocaleTag.EN_IN))

        val otherPayer = money(120_000, listOf(me to 60_000L, rahul to 60_000L), payer = rahul)
        val samePrice = projected(a, conflict = listOf(a, otherPayer))
        assertEquals(
            "never \"Keep ₹1,200.00 or ₹1,200.00?\"",
            "Two devices changed who paid or how it is split. Keep which?",
            ExpenseEditingUi.conflictPrompt(samePrice, LocaleTag.EN_IN),
        )
        assertEquals("Keep this one", ExpenseEditingUi.keepLabel(a, samePrice, LocaleTag.EN_IN))
    }

    // Against a real vault

    private fun seededStore(): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = Files.createTempDirectory("editing-test").resolve("ledger-vault.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        HeldLedgers.group(store, group, listOf(me, rahul)) // decision 208
        store.appendExpense(
            ExpenseDTO(
                payer = me, paidMinor = 120_000, currencyCode = "INR", group = group, conversion = null,
                shares = listOf(ExpenseDTO.ShareDTO(me, 60_000), ExpenseDTO.ShareDTO(rahul, 60_000)),
                note = "Dinner", timestampMs = 1_000, category = null, addedBy = me,
            ),
            nowMs = 5_000,
        )
        return store
    }

    /** Two amount edits that did not see each other (empty `seen`, as two
     *  offline phones would write) are a conflict; keeping one ends it, in the
     *  projection AND in the balances' kv ledger. */
    @Test
    fun keeping_one_amount_ends_the_conflict_in_the_ledger() {
        val store = seededStore()
        val id = store.projectedExpenses().expenses.single().id
        listOf(125_000L to 6_000L, 130_000L to 7_000L).forEach { (paid, at) ->
            store.reviseExpense(
                ExpenseRevisionIn(
                    target = id, seen = emptyList(), editor = rahul,
                    money = money(paid, listOf(me to paid / 2, rahul to paid - paid / 2)),
                    note = null, category = null, deleted = null,
                ),
                nowMs = at,
            )
        }
        val racing = store.projectedExpenses().expenses.single()
        assertEquals(listOf(125_000L, 130_000L), racing.moneyConflict.map { it.paidMinor }.sorted())

        val keep = racing.moneyConflict.single { it.paidMinor == 125_000L }
        store.reviseExpense(ExpenseEditingUi.keep(keep, racing, me), nowMs = 8_000)
        val settled = store.projectedExpenses().expenses.single()
        assertEquals("the kept amount saw both edits", emptyList<ExpenseInput>(), settled.moneyConflict)
        assertEquals(125_000L, settled.expense.paidMinor)
        assertEquals(listOf(125_000L), store.loadExpenses().map { it.paidMinor })
    }

    @Test
    fun delete_then_undo_through_the_helpers_restores_the_expense() {
        val store = seededStore()
        val dinner = store.projectedExpenses().expenses.single()
        store.reviseExpense(ExpenseEditingUi.delete(dinner, me), nowMs = 6_000)
        assertEquals(emptyList<ExpenseDTO>(), store.loadExpenses())
        val gone = store.projectedExpenses().expenses.single()
        assertTrue(gone.deleted)
        store.reviseExpense(ExpenseEditingUi.undo(gone, me), nowMs = 7_000)
        assertEquals(listOf("Dinner"), store.loadExpenses().map { it.note })
    }

    /** System Back steps back through routes; before D-143 it left the app
     *  from any of them (D-136's residue). The tabs stay roots. */
    @Test
    fun back_steps_through_routes_instead_of_leaving_the_app() {
        // D-206: their groups on You now.
        assertEquals("account/security", HomeRoutes.parent("devices"))
        assertEquals("account/profile", HomeRoutes.parent("signin"))
        assertEquals("expense/$dinnerId", HomeRoutes.parent("expense-edit/$dinnerId/detail"))
        assertNull("an edit opened from the list goes back to the list", HomeRoutes.parent("expense-edit/$dinnerId/list"))
        assertNull(HomeRoutes.parent("expense/$dinnerId"))
        assertNull(HomeRoutes.parent("account"))
        assertNull(HomeRoutes.parent(null))
    }

    @Test
    fun the_list_counts_in_the_right_number() {
        assertEquals("1 expense", ExpenseEditingUi.countLabel(1))
        assertEquals("3 expenses", ExpenseEditingUi.countLabel(3))
    }
}
