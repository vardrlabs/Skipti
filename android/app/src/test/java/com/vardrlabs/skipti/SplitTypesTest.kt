package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

private val roster = listOf(BalanceFixtures.ada, BalanceFixtures.rahul, BalanceFixtures.priya)

/**
 * §5.4's six remaining split types, golden-per-type against the REAL core
 * through the host dylib (the same JNA bindings the app uses). THE RULE
 * THAT HOLDS HARDEST HERE: every split is computed by ffiSplitCompute,
 * no Kotlin arithmetic, no percentage maths, no tax/tip apportionment in
 * the shell. Each type also gets its own REFUSAL, asserted as CORE's.
 */
class SplitTypesTest {

    private class MemoryKeystore : SkiptiKeystore {
        var key: ByteArray? = null
        override fun provision(key: ByteArray) {
            this.key = key
        }

        override fun load(): ByteArray? = key
        override fun destroy() {
            key = null
        }
    }

    /** ONE keystore per vault: every open of the same path reuses it, so
     *  repeated builds against one fixture unlock instead of WrongKeying. */
    private class VaultFixture {
        private val keystore = MemoryKeystore()
        val path: String =
            Files.createTempDirectory("split-test").resolve("ledger-vault.db").toString()

        private val opener: () -> uniffi.skipti_core.SkiptiVault = {
            SkiptiVault.open(path, keystore)
        }

        fun build(
            amount: String,
            currency: String,
            draft: AddExpenseUi.SplitDraft,
        ): ExpenseDTO = AddExpenseUi.buildExpense(
            amountText = amount,
            currency = currency,
            note = "",
            category = null,
            participants = roster.toSet(),
            draft = draft,
            payer = BalanceFixtures.ada,
            groupId = BalanceFixtures.ada,
        )
    }

    private val rosterOutside: List<String> = roster

    private fun sharesOf(expense: ExpenseDTO): List<Long> = expense.shares.map { it.amountMinor }
    private fun idsOf(expense: ExpenseDTO): List<String> = expense.shares.map { it.id }

    // ---- Exact ----

    @Test
    fun exact_entries_pass_through_core_verbatim() {
        val fixture = VaultFixture()
        val e = fixture.build(
                "25.00", "USD",
                AddExpenseUi.SplitDraft.Exact(
                amounts = mapOf(
                    BalanceFixtures.ada to "10.00",
                    BalanceFixtures.rahul to "8.34",
                    BalanceFixtures.priya to "6.66",
                ),
            ),
            )
        // Exact: entries must sum to the total; core returns them as given.
        assertEquals(2_500L, e.paidMinor)
        assertEquals(listOf(1_000L, 834L, 666L), sharesOf(e))
        assertEquals(roster, idsOf(e))
    }

    @Test
    fun exact_entries_that_do_not_sum_are_refused_by_core() {
        val fixture = VaultFixture()
        try {
            fixture.build(
                "25.00", "USD",
                AddExpenseUi.SplitDraft.Exact(
                    amounts = mapOf(
                        BalanceFixtures.ada to "10.00",
                        BalanceFixtures.rahul to "10.00",
                        BalanceFixtures.priya to "10.00",
                    ),
                ),
                )
            fail("30.00 of exact entries against a 25.00 total must be refused")
        } catch (core: CoreException) {
            // THE REFUSAL IS CORE'S: surfaced, not a Kotlin pre-check.
            assertTrue(AddExpenseUi.humanError(core).isNotBlank())
        }
    }

    // ---- Percentage ----

    @Test
    fun percentage_golden_with_largest_remainder() {
        val fixture = VaultFixture()
        // 33/33/34 percent of ₹100: 33/33/34 minor exactly.
        val e = fixture.build(
                "100.00", "INR",
                AddExpenseUi.SplitDraft.Percentage(
                percents = mapOf(
                    BalanceFixtures.ada to "33",
                    BalanceFixtures.rahul to "33",
                    BalanceFixtures.priya to "34",
                ),
            ),
            )
        assertEquals(10_000L, e.paidMinor)
        assertEquals(listOf(3_300L, 3_300L, 3_400L), sharesOf(e))
    }

    @Test
    fun percentage_that_does_not_reach_100_is_refused_by_core() {
        val fixture = VaultFixture()
        try {
            fixture.build(
                "100.00", "INR",
                AddExpenseUi.SplitDraft.Percentage(
                    percents = mapOf(
                        BalanceFixtures.ada to "50",
                        BalanceFixtures.rahul to "40",
                        BalanceFixtures.priya to "10",
                    ),
                ),
                )
            // sums to 100 but the bps are 5000+4000+1000 = 10000: this one
            // PASSES; the real negative is below.
            Unit
        } catch (_: CoreException) {
            fail("50/40/10 sums to 100% and must not be refused")
        }
        try {
            fixture.build(
                "100.00", "INR",
                AddExpenseUi.SplitDraft.Percentage(
                    percents = mapOf(
                        BalanceFixtures.ada to "50",
                        BalanceFixtures.rahul to "40",
                        BalanceFixtures.priya to "5",
                    ),
                ),
                )
            fail("95% must be refused by core")
        } catch (core: CoreException) {
            assertTrue(AddExpenseUi.humanError(core).isNotBlank())
        }
    }

    @Test
    fun non_integer_percent_is_the_shells_own_refusal() {
        val fixture = VaultFixture()
        try {
            fixture.build(
                "100.00", "INR",
                AddExpenseUi.SplitDraft.Percentage(
                    percents = mapOf(
                        BalanceFixtures.ada to "12.5",
                        BalanceFixtures.rahul to "50",
                        BalanceFixtures.priya to "37",
                    ),
                ),
                )
            fail("12.5% has no whole-percent form and must be refused")
        } catch (expected: IllegalArgumentException) {
            // THE SHELL'S OWN RULE, mirroring iOS: core takes basis points,
            // and a half-percent has no integer bps without float maths.
        }
    }

    // ---- Shares ----

    @Test
    fun shares_golden_weights_not_amounts() {
        val fixture = VaultFixture()
        // 2:2:1 over $10.00 → 4.00 / 4.00 / 2.00 (weights, not amounts).
        val e = fixture.build(
                "10.00", "USD",
                AddExpenseUi.SplitDraft.Shares(
                weights = mapOf(
                    BalanceFixtures.ada to "2",
                    BalanceFixtures.rahul to "2",
                    BalanceFixtures.priya to "1",
                ),
            ),
            )
        assertEquals(1_000L, e.paidMinor)
        assertEquals(listOf(400L, 400L, 200L), sharesOf(e))
    }

    @Test
    fun shares_remainder_goes_to_largest_remainder_ties_by_ascending_id() {
        val fixture = VaultFixture()
        // 1:1:1 over ₹10.00 → 334/333/333 (D-06: the extra paisa to the
        // largest remainder, ties broken by ascending participant id:
        // the FIRST id in byte order absorbs it).
        val e = fixture.build(
                "10.00", "INR",
                AddExpenseUi.SplitDraft.Shares(
                weights = mapOf(
                    BalanceFixtures.ada to "1",
                    BalanceFixtures.rahul to "1",
                    BalanceFixtures.priya to "1",
                ),
            ),
            )
        assertEquals(1_000L, e.paidMinor)
        assertEquals(listOf(334L, 333L, 333L), sharesOf(e))
        assertEquals(BalanceFixtures.ada, idsOf(e).first())
    }

    // ---- Adjustment ----

    @Test
    fun adjustment_golden_equal_split_plus_deltas() {
        val fixture = VaultFixture()
        // ₹10.00, equal thirds, deltas +1.00 / −1.00 / 0:
        // equal 334/333/333 + deltas → 434/233/333. Sum still 1,000.
        val e = fixture.build(
                "10.00", "INR",
                AddExpenseUi.SplitDraft.Adjustment(
                deltas = mapOf(
                    BalanceFixtures.ada to "1.00",
                    BalanceFixtures.rahul to "-1.00",
                    BalanceFixtures.priya to "0.00",
                ),
            ),
            )
        assertEquals(1_000L, e.paidMinor)
        assertEquals(listOf(434L, 233L, 333L), sharesOf(e))
    }

    @Test
    fun adjustment_exceeding_the_total_is_refused_by_core() {
        val fixture = VaultFixture()
        try {
            // Deltas of +20.00 against a 10.00 total: the residual the equal
            // split must cover is negative: core refuses (D-04).
            fixture.build(
                "10.00", "INR",
                AddExpenseUi.SplitDraft.Adjustment(
                    deltas = mapOf(
                        BalanceFixtures.ada to "20.00",
                        BalanceFixtures.rahul to "20.00",
                        BalanceFixtures.priya to "20.00",
                    ),
                ),
                )
            fail("adjustments exceeding the total must be refused")
        } catch (core: CoreException) {
            assertTrue(AddExpenseUi.humanError(core).isNotBlank())
        }
    }

    // ---- Itemised ----

    @Test
    fun itemised_identity_items_plus_tax_plus_tip_and_shares_sum_to_it() {
        val fixture = VaultFixture()
        // Lines: 6.00 to {ada, rahul}, 4.00 to {priya}; tax 0.50, tip 0.50.
        // Total = 11.00. Line amounts go to their assignees; the POOLED
        // tax+tip (1.00) splits equally across the UNION of assignees
        // (D-05): the union is all three, so the third is 33/33/34 by
        // largest remainder. THE IDENTITY: items + tax + tip == the total,
        // and the shares sum to it: pinned where a naive apportionment
        // would round wrong.
        val e = fixture.build(
                "11.00", "INR",
                AddExpenseUi.SplitDraft.Itemised(
                lines = listOf(
                    AddExpenseUi.SplitDraft.LineDraft(
                        amount = "6.00",
                        who = setOf(BalanceFixtures.ada, BalanceFixtures.rahul),
                    ),
                    AddExpenseUi.SplitDraft.LineDraft(
                        amount = "4.00",
                        who = setOf(BalanceFixtures.priya),
                    ),
                ),
                tax = "0.50",
                tip = "0.50",
            ),
            )
        assertEquals(1_100L, e.paidMinor)
        val shares = sharesOf(e)
        assertEquals("shares sum to items+tax+tip", 1_100L, shares.sum())
        // Line parts: ada 300, rahul 300, priya 400; pooled 100 across the
        // union → +34/+33/+33 by D-06's largest remainder.
        assertEquals(
            mapOf(
                BalanceFixtures.ada to 334L,
                BalanceFixtures.rahul to 333L,
                BalanceFixtures.priya to 433L,
            ),
            e.shares.associate { it.id to it.amountMinor },
        )
    }

    @Test
    fun itemised_line_assigned_to_nobody_is_refused_by_core() {
        val fixture = VaultFixture()
        try {
            fixture.build(
                "10.00", "INR",
                AddExpenseUi.SplitDraft.Itemised(
                    lines = listOf(
                        AddExpenseUi.SplitDraft.LineDraft(amount = "6.00", who = emptySet()),
                    ),
                    tax = "0.00",
                    tip = "0.00",
                ),
                )
            fail("an itemised line assigned to nobody must be refused")
        } catch (core: CoreException) {
            assertTrue(AddExpenseUi.humanError(core).isNotBlank())
        }
    }

    // ---- PayerOnly ----

    @Test
    fun payer_only_nobody_owes() {
        val fixture = VaultFixture()
        val e = fixture.build(
            "50.00", "INR",
            AddExpenseUi.SplitDraft.PayerOnly,
        )
        assertEquals(5_000L, e.paidMinor)
        // One person paid, nobody owes (§5.4): the payer carries the whole
        // amount; every other member's share is zero.
        val byId = e.shares.associate { it.id to it.amountMinor }
        assertEquals(5_000L, byId[BalanceFixtures.ada])
        assertTrue(byId.filterKeys { it != BalanceFixtures.ada }.values.all { it == 0L })
    }

    // ---- Cross-type conservation ----

    @Test
    fun every_type_shares_sum_to_the_total() {
        val fixture = VaultFixture()
        val drafts = listOf(
            AddExpenseUi.SplitDraft.Equal as AddExpenseUi.SplitDraft,
            AddExpenseUi.SplitDraft.Exact(
                amounts = mapOf(
                    BalanceFixtures.ada to "3.00",
                    BalanceFixtures.rahul to "2.00",
                    BalanceFixtures.priya to "2.50",
                ),
            ),
            AddExpenseUi.SplitDraft.Percentage(
                percents = mapOf(
                    BalanceFixtures.ada to "40",
                    BalanceFixtures.rahul to "40",
                    BalanceFixtures.priya to "20",
                ),
            ),
            AddExpenseUi.SplitDraft.Shares(
                weights = mapOf(
                    BalanceFixtures.ada to "3",
                    BalanceFixtures.rahul to "1",
                    BalanceFixtures.priya to "1",
                ),
            ),
            AddExpenseUi.SplitDraft.Adjustment(
                deltas = mapOf(
                    BalanceFixtures.ada to "0.50",
                    BalanceFixtures.rahul to "-0.50",
                    BalanceFixtures.priya to "0.00",
                ),
            ),
            AddExpenseUi.SplitDraft.Itemised(
                lines = listOf(
                    AddExpenseUi.SplitDraft.LineDraft(
                        amount = "7.00",
                        who = setOf(BalanceFixtures.ada, BalanceFixtures.rahul),
                    ),
                ),
                tax = "0.25",
                tip = "0.25",
            ),
            AddExpenseUi.SplitDraft.PayerOnly,
        )
        for (draft in drafts) {
            val e = fixture.build(
                "7.50", "INR",
                draft,
            )
            assertEquals(
                "shares must sum to the total for ${draft.label}",
                750L,
                sharesOf(e).sum(),
            )
        }
    }

    /** D-225 T7 (Design 10, 11): the Split type choice page offers §5.4's seven kinds in iOS's order and words
     *  (`SplitKind.allCases`), and picking one starts it fresh: its entries empty, never a kind's leftovers. */
    @Test
    fun the_split_type_choices_are_ios_kinds_and_start_fresh() {
        assertEquals(
            listOf("Equal", "Exact", "Percentage", "Shares", "Adjustment", "Itemised", "Payer only"),
            AddExpenseUi.allKinds.map { it.label },
        )
        for (label in AddExpenseUi.allKinds.map { it.label }) assertEquals(label, AddExpenseUi.kind(label).label)
        assertEquals(AddExpenseUi.SplitDraft.Shares(), AddExpenseUi.kind("Shares"))
        assertEquals(AddExpenseUi.SplitDraft.Itemised(), AddExpenseUi.kind("Itemised"))
        assertEquals(AddExpenseUi.SplitDraft.PayerOnly, AddExpenseUi.kind("Payer only"))
    }
}
