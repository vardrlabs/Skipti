package com.vardrlabs.skipti

import com.vardrlabs.skipti.AddExpenseUi.SplitDraft
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.ExpenseRevisionIn
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.SplitSpecInput
import uniffi.skipti_core.ffiMoneyFormat
import uniffi.skipti_core.ffiMoneyInputDigits
import uniffi.skipti_core.ffiSplitCompute

/**
 * D-143: what Edit, Delete, Undo and the amount-conflict prompt WRITE, the
 * twin of iOS `ExpenseEditing` (D-142), kept out of the composables so the
 * JVM tests drive it against the real core. SPEC §5.7: an edit is a new
 * version and a delete a tombstone, through D-141's revision records. SPEC
 * §6.1: an amount conflict is put to the person, never resolved silently.
 *
 * Every revision carries the `seen` of the projection it was made from, so
 * core can tell an edit that saw another from one that raced it. No money
 * arithmetic happens here (ADR-0002): amounts come from core as digits,
 * shares from `ffiSplitCompute`, and the only comparisons are between
 * core's own outputs.
 */
object ExpenseEditingUi {
    /** The Add form's starting state for an edit. */
    data class Prefill(
        val amountText: String,
        val currency: String,
        val note: String,
        val category: String?,
        val payer: String,
        val participants: Set<String>,
        /** Exact carries each person's stored share as digits. */
        val draft: SplitDraft,
    )

    /**
     * The form a stored expense opens in. The split TYPE is not stored, only
     * the shares it produced, so it is re-derived: if core's payer-only or
     * equal split over the same people gives exactly these shares, the form
     * opens as that type, so a changed amount re-splits the way the person
     * expects; anything else opens as Exact with each stored share, which
     * reproduces it to the minor unit.
     */
    fun prefill(p: ProjectedExpenseOut): Prefill {
        val e = p.expense
        val roster = e.shares.map { it.id }.sorted()
        val stored = normalised(e.shares)
        fun reproduces(spec: SplitSpecInput): Boolean = runCatching {
            normalised(
                ffiSplitCompute(e.paidMinor, e.currencyCode, roster, spec)
                    .map { IdAmount(id = it.id, amountMinor = it.minorUnits) },
            ) == stored
        }.getOrDefault(false)
        val draft: SplitDraft = when {
            reproduces(SplitSpecInput.PayerOnly(payer = e.payer)) -> SplitDraft.PayerOnly
            reproduces(SplitSpecInput.Equal) -> SplitDraft.Equal
            else -> SplitDraft.Exact(
                e.shares.associate { it.id to ffiMoneyInputDigits(it.amountMinor, e.currencyCode) },
            )
        }
        return Prefill(
            amountText = ffiMoneyInputDigits(e.paidMinor, e.currencyCode),
            currency = e.currencyCode,
            note = p.note ?: "",
            category = p.category,
            payer = e.payer,
            participants = roster.toSet(),
            draft = draft,
        )
    }

    /**
     * The revision an edit writes: only the fields that changed (SPEC §6.1's
     * per-field registers, so a note left alone never overwrites a note
     * another device changed meanwhile), or null when nothing did.
     */
    fun revision(
        editing: ProjectedExpenseOut,
        money: ExpenseInput,
        note: String,
        category: String?,
        editor: String?,
    ): ExpenseRevisionIn? {
        val old = editing.expense
        // A frozen conversion describes one amount in one currency (§5.2); a
        // new amount or currency leaves it behind.
        val sameAmount = money.paidMinor == old.paidMinor && money.currencyCode == old.currencyCode
        val newMoney = money.copy(
            // An edit never moves an expense between groups (D-141).
            group = old.group,
            conversion = if (sameAmount) old.conversion else null,
        )
        val moneyChanged = !sameMoney(newMoney, old)
        val newNote = note.ifEmpty { null }
        val noteChanged = newNote != editing.note
        val categoryChanged = category != editing.category
        if (!moneyChanged && !noteChanged && !categoryChanged) return null
        return ExpenseRevisionIn(
            target = editing.id,
            seen = editing.seen,
            editor = editor,
            money = if (moneyChanged) newMoney else null,
            // "" clears (core's rule): a removed note must be WRITTEN, or the
            // old one would survive the edit.
            note = if (noteChanged) newNote ?: "" else null,
            category = if (categoryChanged) category ?: "" else null,
            deleted = null,
        )
    }

    /** The tombstone (SPEC §5.7): it leaves balances and the list, and stays
     *  in the records, recoverable. */
    fun delete(p: ProjectedExpenseOut, editor: String?) = ExpenseRevisionIn(
        target = p.id, seen = p.seen, editor = editor,
        money = null, note = null, category = null, deleted = true,
    )

    /** Undo: `deleted` is a register, so bringing an expense back is one more
     *  revision, written from the projection that holds the delete. */
    fun undo(p: ProjectedExpenseOut, editor: String?) = ExpenseRevisionIn(
        target = p.id, seen = p.seen, editor = editor,
        money = null, note = null, category = null, deleted = false,
    )

    /** SPEC §6.1: keeping one amount is a money edit that saw EVERY competing
     *  edit, so it is the only head left and the conflict ends on every
     *  device that receives it. */
    fun keep(choice: ExpenseInput, of: ProjectedExpenseOut, editor: String?) = ExpenseRevisionIn(
        target = of.id, seen = of.seen, editor = editor,
        money = choice.copy(group = of.expense.group), note = null, category = null, deleted = null,
    )

    /**
     * SPEC §6.1's sentence, verbatim for two devices: "Two devices set
     * different amounts. Keep ₹1,200 or ₹1,250?" Amounts come from core.
     * When the competing versions share an amount (a different payer or
     * split), naming amounts would ask "₹1,200 or ₹1,200?", so it says so.
     */
    fun conflictPrompt(p: ProjectedExpenseOut, locale: LocaleTag): String? {
        val options = p.moneyConflict
        if (options.size < 2) return null
        val who = if (options.size == 2) "Two devices" else "${options.size} devices"
        val amounts = options.map { amount(it, locale) }
        if (amounts.toSet().size != amounts.size) {
            return "$who changed who paid or how it is split. Keep which?"
        }
        val list = amounts.dropLast(1).joinToString(", ")
        return "$who set different amounts. Keep $list or ${amounts.last()}?"
    }

    /** One option's button: its amount, unless amounts cannot tell the
     *  options apart. */
    fun keepLabel(option: ExpenseInput, p: ProjectedExpenseOut, locale: LocaleTag): String {
        val amounts = p.moneyConflict.map { amount(it, locale) }
        return if (amounts.toSet().size != amounts.size) "Keep this one" else "Keep ${amount(option, locale)}"
    }

    /** D-143, the twin of D-142's fix: "EXPENSES · 1 RECORDS" said the same
     *  thing twice, in the wrong number. */
    fun countLabel(count: Int): String = if (count == 1) "1 expense" else "$count expenses"

    fun amount(money: ExpenseInput, locale: LocaleTag): String =
        runCatching { ffiMoneyFormat(money.paidMinor, money.currencyCode, locale) }.getOrDefault(Placeholder.NONE)

    /** Shares compared as a set of (person, amount): the same split in a
     *  different order is the same split. */
    private fun normalised(shares: List<IdAmount>): List<IdAmount> =
        shares.sortedWith(compareBy<IdAmount> { it.id }.thenBy { it.amountMinor })

    private fun sameMoney(a: ExpenseInput, b: ExpenseInput): Boolean =
        a.payer == b.payer && a.paidMinor == b.paidMinor && a.currencyCode == b.currencyCode &&
            a.conversion == b.conversion && normalised(a.shares) == normalised(b.shares)
}
