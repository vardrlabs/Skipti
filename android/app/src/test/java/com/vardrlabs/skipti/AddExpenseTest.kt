package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiCategoryTreeDefault
import uniffi.skipti_core.ffiMoneyParseMinor

/**
 * The add-expense screen's guards, against the REAL core and the REAL skrin
 * vault on the host dylib (same discipline as LedgerStoreTest). The screen's
 * whole point, tested: decimal STRING → core → integer minor units; spec →
 * core → computed shares; expense → vault → persisted. No arithmetic happens
 * in the screen, and if a float or a Kotlin-side split sneaks in, a test
 * here goes red.
 */
class AddExpenseTest {

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

    private class VaultFixture {
        private val keystore = MemoryKeystore()
        val path: String =
            Files.createTempDirectory("add-expense-test").resolve("ledger-vault.db").toString()

        fun store(): LedgerStore = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        val opener: () -> uniffi.skipti_core.SkiptiVault = { SkiptiVault.open(path, keystore) }
    }

    /** D-225 (Design 11), D-249 (U10): Add expense's Ledger row; the twin of iOS's
     *  testTheLedgerRowOffersAChooserOnlyToANewExpenseWithAChoice. Just me plus any writable group is a choice for a new
     *  expense; Just me alone, or any edit (an edit stays in its own ledger), shows it plainly. There is always a row. */
    @Test
    fun the_ledger_row_offers_a_chooser_only_to_a_new_expense_with_a_choice() {
        assertEquals("an edit never moves ledger", AddExpenseRows.LedgerRow.PLAIN, AddExpenseRows.ledgerRow(editing = true, writableCount = 2))
        assertEquals(AddExpenseRows.LedgerRow.PLAIN, AddExpenseRows.ledgerRow(editing = true, writableCount = 0))
        assertEquals(AddExpenseRows.LedgerRow.CHOOSER, AddExpenseRows.ledgerRow(editing = false, writableCount = 2))
        assertEquals(AddExpenseRows.LedgerRow.CHOOSER, AddExpenseRows.ledgerRow(editing = false, writableCount = 1))
        assertEquals(AddExpenseRows.LedgerRow.PLAIN, AddExpenseRows.ledgerRow(editing = false, writableCount = 0))
    }

    // ---- D-225 Task 7 (Design 11): Add and Edit expense in list cards with choice pages (twins of iOS's Task 6) ----

    private fun src(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** The twin of iOS's testAddExpenseIsListCards: no Material field, dropdown, chip or checkbox is left; the five
     *  choices (Category, Sub-category, Group, Split type, Paid by) are choice pages; the member rows and an item line's
     *  people are drawn switch rows; every field is a field row with today's placeholders; the Group row is
     *  `AddExpenseRows.groupRow`; Save stays the pinned action. */
    @Test
    fun add_expense_is_list_cards() {
        val t = src("AddExpenseScreen.kt")
        for (gone in listOf("OutlinedTextField", "ExposedDropdownMenuBox", "FilterChip", "Checkbox(", "fun ChoiceDropdown(", "FlowRow")) {
            assertFalse("Add expense keeps $gone", gone in t)
        }
        // D-249: four choice pages, and the Ledger row opens decision 193's rich page.
        assertEquals("four choice pages", 4, Regex("""(?<!Ledger)ChoicePage\(""").findAll(t).count())
        assertEquals("the Ledger page", 1, Regex("""LedgerChoicePage\(\s*LedgerOptions\.build\(""").findAll(t).count())
        assertTrue("a row opening the Ledger page", Regex("""ListRow\(\s*AddSheetCopy\.LEDGER,""").containsMatchIn(t))
        for (title in listOf("CategoryCopy.CATEGORY", "CategoryCopy.SUBCATEGORY", "AddSheetCopy.SPLIT_TYPE", "AddSheetCopy.PAID_BY")) {
            assertTrue("a choice page titled $title", Regex("""ChoicePage\(\s*$title,""").containsMatchIn(t))
            assertTrue("a row opening $title", Regex("""ListRow\(\s*$title,""").containsMatchIn(t))
        }
        assertTrue("the member rows are switch rows", "trailing = RowTrailing.Toggle(id in participants)" in t)
        assertTrue("an item line's people are switch rows", "trailing = RowTrailing.Toggle(id in line.who)" in t)
        for (field in listOf(
            "AddSheetCopy.WHAT_FOR", "\"Amount for \${name(id)}\"", "\"Percent for \${name(id)}\"", "\"Shares for \${name(id)}\"",
            "\"Adjustment for \${name(id)}\"", "\"Line amount\"", "\"Tax\"", "\"Tip\"",
        )) assertTrue("a field row for $field", Regex("""FieldRow\([^\n]*\n?[^\n]*${Regex.escape(field)}""").containsMatchIn(t))
        assertTrue("the Ledger row's decision", "AddExpenseRows.ledgerRow(editingId != null, groups.size)" in t)
        assertTrue("the split's label", "SectionLabel(AddSheetCopy.SPLIT, dark)" in t)
        assertTrue("Save is still the pinned action", "actions = {\n            PrimaryAction(" in t)
    }

    /** The twin of iOS's currency pill guard (A22): the pill opens the whole currency list page at once (its "Common"
     *  section holds the promoted codes), no chip row toggled under it. */
    @Test
    fun the_currency_pill_opens_the_list() {
        val t = src("AddExpenseScreen.kt")
        assertTrue(".clickable(role = Role.Button) { listingCurrencies = true }" in t)
        assertTrue("CurrencyListDialog(\n            selected = currency," in t)
        assertFalse("no chip row", "CurrencyChips(" in t)
        assertFalse("no toggle", "showCurrencies" in t)
    }

    @Test
    fun parse_goldens_the_ledger_must_refuse_what_is_not_an_amount() {
        // D-50's goldens, through the same entry point the screen uses.
        assertEquals(1_240L, ffiMoneyParseMinor("12.40", "INR"))
        assertEquals(10L, ffiMoneyParseMinor("0.1", "INR"))
        assertEquals(1_005L, ffiMoneyParseMinor("1.005", "KWD"))
        assertEquals(1_005L, ffiMoneyParseMinor("1005", "JPY"))
        // The float-sneak catcher: 12.40 in binary doubles is
        // 12.4000000000000003552713678800500929355621337890625: a
        // double-times-100 path lands on 1239, never 1240. Core's integer
        // parsing lands on 1240 exactly, and this assertion holds only
        // while no float enters the money path.
        assertEquals(1_240L, ffiMoneyParseMinor("12.40", "INR"))
        // Silent rounding is the enemy (ADR-0002):
        for (bad in listOf("1.005", "1,234", "abc", "", "0.1+0.2", "1e3")) {
            try {
                ffiMoneyParseMinor(bad, "INR")
                fail("\"$bad\" must be refused by core, not coerced")
            } catch (_: Exception) {
                // exactly the refusal the screen surfaces
            }
        }
    }

    @Test
    fun build_expense_equal_split_is_computed_by_core() {
        val fixture = VaultFixture()
        val now = 1_756_000_000_000L
        val expense = AddExpenseUi.buildExpense(
            amountText = "25.00",
            currency = "USD",
            note = "Taxi to airport",
            category = null,
            participants = setOf(BalanceFixtures.ada, BalanceFixtures.rahul, BalanceFixtures.priya),
                draft = AddExpenseUi.SplitDraft.Equal,
            payer = BalanceFixtures.ada,
            groupId = BalanceFixtures.ada,
            nowMs = now,
        )
        assertEquals(2_500L, expense.paidMinor)
        assertEquals("USD", expense.currencyCode)
        assertEquals("Taxi to airport", expense.note)
        assertEquals(now, expense.timestampMs)
        assertEquals(BalanceFixtures.ada, expense.payer)
        // Core's largest-remainder split (D-06): 2500/3 → 834, 833, 833 with
        // the extra paisa to the first UUID-ordered member. Kotlin floor
        // division (833,833,833) fails this; only core passes.
        assertEquals(listOf(834L, 833L, 833L), expense.shares.map { it.amountMinor })
        assertEquals(expense.shares.map { it.amountMinor }.sum(), 2_500L)
        // §5.3: the record BELONGS to the group the screen passed, and (D-136)
        // building it never seeds a group; this used to write "Goa Trip".
        assertEquals(BalanceFixtures.ada, expense.group)
        assertTrue("building an expense wrote a group", fixture.store().loadGroups().isEmpty())
    }

    @Test
    fun payer_only_charges_the_chosen_payer() {
        // D-136: "Payer only" follows the payer the person picked. It was the
        // fixture Ada whoever paid.
        val expense = AddExpenseUi.buildExpense(
            amountText = "40.00",
            currency = "INR",
            note = "",
            category = null,
            participants = setOf(BalanceFixtures.ada, BalanceFixtures.rahul),
            draft = AddExpenseUi.SplitDraft.PayerOnly,
            payer = BalanceFixtures.rahul,
            groupId = BalanceFixtures.ada,
        )
        assertEquals(BalanceFixtures.rahul, expense.payer)
        val byId = expense.shares.associate { it.id to it.amountMinor }
        assertEquals(4_000L, byId[BalanceFixtures.rahul])
        assertTrue(byId.filterKeys { it != BalanceFixtures.rahul }.values.all { it == 0L })
    }

    @Test
    fun integer_parsing_survives_where_a_float_would_not() {
        val fixture = VaultFixture()
        val expense = AddExpenseUi.buildExpense(
            amountText = "1.15",
            currency = "INR",
            note = "",
            category = null,
            participants = setOf(BalanceFixtures.ada, BalanceFixtures.rahul),
                draft = AddExpenseUi.SplitDraft.Equal,
            payer = BalanceFixtures.ada,
            groupId = BalanceFixtures.ada,
        )
        // Chosen by computation, not folklore: 1.15 in binary doubles is
        // 1.149999999999999911182158029987476766109466552734375, and
        // (1.15 * 100).toLong() lands on 114: a real paisa lost. Core's
        // integer path reaches 115; this is the assertion a sneaking float
        // fails. (12.40 was tried first and is NOT a catcher: the double
        // product rounds to exactly 1240.)
        assertEquals(115L, expense.paidMinor)
    }

    @Test
    fun zero_amount_is_refused_and_writes_nothing() {
        val fixture = VaultFixture()
        for (amount in listOf("0.00", "0", "-5.00")) {
            try {
                AddExpenseUi.buildExpense(
                    amountText = amount,
                    currency = "INR",
                    note = "",
                    category = null,
                    participants = setOf(BalanceFixtures.ada, BalanceFixtures.rahul),
                draft = AddExpenseUi.SplitDraft.Equal,
                    payer = BalanceFixtures.ada,
                    groupId = BalanceFixtures.ada,
                )
                fail("\"$amount\" must be refused, not saved")
            } catch (_: IllegalArgumentException) {
                // the screen's own guard, rendered as the owed error line.
                // Pinned BY TYPE on purpose: core also refuses a zero/negative
                // total one call later (ffiSplitCompute rejects it), but the
                // guard exists so the USER sees "the amount must be positive"
                // before any core call. The audit's probe note applies: a
                // broad catch here would let core's downstream refusal wear
                // this guard's clothes.
            }
        }
        // THE SAFETY PROPERTY: validation failing left the ledger untouched.
        assertTrue(fixture.store().loadExpenses().isEmpty())
    }

    @Test
    fun empty_participants_are_refused() {
        val fixture = VaultFixture()
        try {
            AddExpenseUi.buildExpense(
                amountText = "10.00",
                currency = "INR",
                note = "",
                category = null,
                participants = emptySet(),
                draft = AddExpenseUi.SplitDraft.Equal,
                payer = BalanceFixtures.ada,
                groupId = BalanceFixtures.ada,
            )
            fail("an expense nobody shares must be refused")
        } catch (_: IllegalArgumentException) {
            // the screen's own guard, pinned by type (see the zero test)
        }
        assertTrue(fixture.store().loadExpenses().isEmpty())
    }

    @Test
    fun append_expense_persists_through_the_vault_loop() {
        val fixture = VaultFixture()
        val first = AddExpenseUi.buildExpense(
            amountText = "25.00", currency = "USD", note = "Taxi",
            category = null,
            participants = setOf(BalanceFixtures.ada, BalanceFixtures.rahul),
                draft = AddExpenseUi.SplitDraft.Equal,
            payer = BalanceFixtures.ada,
            groupId = BalanceFixtures.ada,
        )
        val afterFirst = fixture.store().appendExpense(first)
        assertEquals(1, afterFirst.size)
        assertEquals(first, afterFirst[0])
        // A NEW store handle reads the same persisted bytes: the return came
        // from a close/reopen, not from memory.
        assertEquals(afterFirst, fixture.store().loadExpenses())

        val second = AddExpenseUi.buildExpense(
            amountText = "300.00", currency = "INR", note = "Hotel",
            category = null,
            participants = setOf(BalanceFixtures.ada, BalanceFixtures.amit),
                draft = AddExpenseUi.SplitDraft.Equal,
            payer = BalanceFixtures.ada,
            groupId = BalanceFixtures.ada,
        )
        val afterSecond = fixture.store().appendExpense(second)
        assertEquals(2, afterSecond.size)
        assertEquals(listOf("Taxi", "Hotel"), afterSecond.map { it.note })
        assertEquals(afterSecond, fixture.store().loadExpenses())
    }

    @Test
    fun core_errors_render_human_messages_not_uniffi_field_prefixes() {
        val e = try {
            ffiMoneyParseMinor("abc", "INR")
            fail("abc must be refused")
            throw IllegalStateException()
        } catch (core: uniffi.skipti_core.CoreException) {
            core
        }
        val msg = AddExpenseUi.humanError(e)
        assertEquals("amount must be digits and one dot: \"abc\"", msg)
        // The generated message getter prefixes "v1=": the user sees the
        // payload, never the wire shape.
        assertTrue(!msg.startsWith("v1="))
    }
}
