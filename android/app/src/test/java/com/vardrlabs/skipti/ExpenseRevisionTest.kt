package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.ExpenseRevisionIn
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * Editing and deleting expenses on Android (D-141; SPEC §5.7, §6.1), the twin
 * of iOS ExpenseRevisionTests, against a real vault through core's one
 * projection: an added expense gets a stable id and "added by"; an edit is a
 * new record that changes the ledger and says who edited it; a delete leaves
 * the ledger and Undo brings it back; and the legacy backfill never re-mints
 * an edited row as a duplicate expense. In the shared test set: both flavours
 * run it.
 */
class ExpenseRevisionTest {

    private class Fixture {
        private val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path: String =
            Files.createTempDirectory("revisions-test").resolve("ledger-vault.db").toString()
        private var held = false
        // Decision 208: the vault holds the group its expenses are written into.
        fun store(): LedgerStore = LedgerStore(path) { SkiptiVault.open(path, keystore) }.also {
            if (!held) { held = true; HeldLedgers.group(it, "0".repeat(31) + "9", listOf("0".repeat(31) + "1", "0".repeat(31) + "2")) }
        }
    }

    private val me = "0".repeat(31) + "1"
    private val rahul = "0".repeat(31) + "2"
    private val group = "0".repeat(31) + "9"

    private fun expense(amount: Long, note: String) = ExpenseDTO(
        payer = me,
        paidMinor = amount,
        currencyCode = "INR",
        group = group,
        conversion = null,
        shares = listOf(
            ExpenseDTO.ShareDTO(id = me, amountMinor = amount / 2),
            ExpenseDTO.ShareDTO(id = rahul, amountMinor = amount - amount / 2),
        ),
        note = note,
        timestampMs = 1_000,
        category = null,
        addedBy = me,
    )

    private fun edit(
        id: String,
        seen: List<String> = emptyList(),
        money: ExpenseInput? = null,
        note: String? = null,
        deleted: Boolean? = null,
    ) = ExpenseRevisionIn(
        target = id, seen = seen, editor = rahul, money = money, note = note,
        category = null, deleted = deleted,
    )

    /** The expense and revision records (decision 208: the held group's own backfilled record is not counted). */
    private fun moneyRecords(store: LedgerStore): Int =
        store.allRecords().count { it.recordType == 1u.toUByte() || it.recordType == LedgerStore.REVISION_TYPE }

    private fun onlyExpense(store: LedgerStore): ProjectedExpenseOut {
        val all = store.projectedExpenses().expenses
        assertEquals(1, all.size)
        return all.single()
    }

    @Test
    fun an_added_expense_has_a_stable_id_and_who_added_it() {
        val s = Fixture().store()
        s.appendExpense(expense(900, "dinner"), nowMs = 5_000)
        val e = onlyExpense(s)
        assertEquals(LedgerStore.recordIdentity(s.allRecords().single()), e.id)
        assertEquals(me, e.addedBy)
        assertNull(e.lastEditedAtMs)
        assertEquals(
            "the kv ledger reads exactly as it did before D-141",
            listOf(expense(900, "dinner")), s.loadExpenses(),
        )
    }

    @Test
    fun a_note_edit_changes_the_ledger_and_says_who_edited_it() {
        val s = Fixture().store()
        s.appendExpense(expense(900, "dinner"), nowMs = 5_000)
        val id = onlyExpense(s).id
        s.reviseExpense(edit(id, note = "taxi"), nowMs = 6_000)
        assertEquals(listOf("taxi"), s.loadExpenses().map { it.note })
        val e = onlyExpense(s)
        assertEquals(rahul, e.lastEditedBy)
        assertEquals(6_000uL, e.lastEditedAtMs)
        assertEquals(1, e.seen.size)
        assertEquals("the edit is a new record; the original stays", 2, moneyRecords(s))
    }

    @Test
    fun a_money_edit_moves_the_amount_and_the_shares() {
        val s = Fixture().store()
        s.appendExpense(expense(900, "dinner"), nowMs = 5_000)
        val id = onlyExpense(s).id
        s.reviseExpense(edit(id, money = expense(1200, "").toInput()), nowMs = 6_000)
        val row = s.loadExpenses().single()
        assertEquals(1200L, row.paidMinor)
        assertEquals(listOf(600L, 600L), row.shares.map { it.amountMinor })
        assertEquals("an edit never moves an expense between groups", group, row.group)
        assertEquals("only the money changed", "dinner", row.note)
    }

    @Test
    fun delete_leaves_the_ledger_and_undo_brings_it_back() {
        val s = Fixture().store()
        s.appendExpense(expense(900, "dinner"), nowMs = 5_000)
        val id = onlyExpense(s).id
        s.reviseExpense(edit(id, deleted = true), nowMs = 6_000)
        assertEquals(emptyList<ExpenseDTO>(), s.loadExpenses())
        val deleted = onlyExpense(s)
        assertTrue("still in the projection, recoverable", deleted.deleted)
        s.reviseExpense(edit(id, seen = deleted.seen, deleted = false), nowMs = 7_000)
        assertEquals(listOf(expense(900, "dinner")), s.loadExpenses())
    }

    /** The trap D-141 found: after an edit the kv row no longer equals its
     *  creation record, and the D-121 backfill would have minted the edited
     *  row as a brand-new expense on the next pull. It now runs once. */
    @Test
    fun the_backfill_never_re_mints_an_edited_expense() {
        val s = Fixture().store()
        s.appendExpense(expense(900, "dinner"), nowMs = 5_000)
        val id = onlyExpense(s).id
        s.reviseExpense(edit(id, note = "taxi"), nowMs = 6_000)
        assertEquals(0, s.backfillLegacyRecords())
        assertEquals(2, moneyRecords(s))
        s.rebuildProjection()
        assertEquals("still ONE expense", listOf("taxi"), s.loadExpenses().map { it.note })
    }

    @Test
    fun revising_an_expense_this_vault_does_not_hold_is_refused() {
        val s = Fixture().store()
        s.appendExpense(expense(900, "dinner"), nowMs = 5_000)
        val stranger = "1:0:" + "0".repeat(31) + "7"
        try {
            s.reviseExpense(edit(stranger, note = "x"))
            fail("must refuse an unknown expense")
        } catch (_: UnknownExpenseException) {
            // refused, as required
        }
        assertEquals("nothing was written", 1, s.allRecords().size)
    }
}
