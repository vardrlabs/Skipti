package com.vardrlabs.skipti

import java.nio.file.Files
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.LineInput
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.SplitSpecInput
import uniffi.skipti_core.ffiMoneyParseMinor
import uniffi.skipti_core.ffiMoneyParseTyped
import uniffi.skipti_core.ffiSplitCompute

/**
 * D-248: the small bugs and friction (U29.2 to U31) on Android, the twin of iOS's D248FixesTests: each item's decision,
 * its words and its negative, against the real core.
 */
class D248FixesTest {
    private val me = "0".repeat(31) + "1"
    private val rahul = "0".repeat(31) + "2"
    private val group = "0".repeat(31) + "9"
    private val en = Locale.US
    private val de = Locale.GERMANY

    private val sources = java.io.File("src/main/java/com/vardrlabs/skipti")
    private fun src(file: String) = java.io.File(sources, file).readText()
    private fun allSources() = sources.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.map { it.name to it.readText() }

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

    // ---- U30.2 the typed-amount parser (decision 184) ----

    /** Core's typed parser through the bindings, given the phone's mark (decision 203): the same subset of
     *  `.drafts/d248/typed_goldens.py`'s table iOS pins (derived there with exact Fractions, never read out of core).
     *  [AmountEntry] passes the mark of the locale it is given. */
    @Test
    fun typed_amounts_read_a_comma_only_on_a_comma_phone() {
        data class Row(val text: String, val code: String, val mark: String, val want: Long?)
        val goldens = listOf(
            Row("12,50", "EUR", ",", 1_250), Row(",5", "EUR", ",", 50), Row("1,005", "KWD", ",", 1_005), Row("1,234", "KWD", ",", 1_234),
            Row("12.50", "EUR", ",", 1_250), Row("1.05", "KWD", ",", 1_050), Row("1234", "JPY", ",", 1_234),
            Row("1.234", "KWD", ",", null), Row("1.234", "EUR", ",", null), Row("10,5", "JPY", ",", null), Row("1,234", "INR", ",", null),
            Row("1.234,56", "EUR", ",", null), Row("1,0005", "KWD", ",", null), Row(",", "EUR", ",", null),
            Row("12.50", "EUR", ".", 1_250), Row("1.234", "KWD", ".", 1_234), Row("1005", "JPY", ".", 1_005), Row(" 7.10 ", "USD", ".", 710),
            Row("1,500", "KWD", ".", null), Row("1,234", "KWD", ".", null), Row("12,50", "EUR", ".", null), Row("1,234,567", "INR", ".", null),
            Row("1,234.56", "USD", ".", null), Row("2,00,000", "INR", ".", null), Row("", "EUR", ".", null),
        )
        for ((text, code, mark, want) in goldens) {
            assertEquals("$code '$text' $mark", want, runCatching { ffiMoneyParseTyped(text, code, mark) }.getOrNull())
            assertEquals("$code '$text' $mark", want, AmountEntry.minor(text, code, if (mark == ",") de else en))
        }
        // A mark that is neither is refused, never guessed.
        assertTrue(runCatching { ffiMoneyParseTyped("12.50", "EUR", "\u066B") }.isFailure)
        // Import's strict parser is untouched: a comma never reads there.
        assertTrue(runCatching { ffiMoneyParseMinor("12,50", "EUR") }.isFailure)
    }

    /** "Enter an amount like {example}.": the example in the currency's own decimals and the phone's decimal mark, both
     *  from core (`ffiMoneyTypedDigits`, review M3); every field starts from core's entry form in that mark (review M6),
     *  which the parser reads back on that phone. */
    @Test
    fun a_non_amount_is_refused_in_words_with_the_locales_mark() {
        assertEquals(".", AmountEntry.mark(en))
        assertEquals(",", AmountEntry.mark(de))
        assertEquals(",", AmountEntry.mark(Locale.FRANCE))
        assertEquals(".", AmountEntry.mark(Locale("en", "IN")))
        assertEquals("Enter an amount like 12.50.", AmountEntry.likeWords("INR", en))
        assertEquals("Enter an amount like 12,50.", AmountEntry.likeWords("EUR", de))
        assertEquals("no decimals in yen", "1250", AmountEntry.example("JPY", de))
        assertEquals("three in dinars", "1,250", AmountEntry.example("KWD", de))
        assertEquals("1.250", AmountEntry.example("KWD", en))
        assertNull("no example is made up", AmountEntry.example("XYZ", en))
        assertEquals("Enter an amount.", AmountEntry.likeWords("XYZ", en))
        assertEquals(1_250L, AmountEntry.parse("12,50", "EUR", de))
        assertEquals(AmountEntry.Refusal("Enter an amount like 12,50."), runCatching { AmountEntry.parse("12,5,0", "EUR", de) }.exceptionOrNull())
        assertEquals(AmountEntry.Refusal("Enter an amount like 1250."), runCatching { AmountEntry.parse("10,5", "JPY", en) }.exceptionOrNull())
        // I1: an English "1,500" in dinars is refused in words, never saved as 1.500 KD.
        assertEquals(AmountEntry.Refusal("Enter an amount like 1.250."), runCatching { AmountEntry.parse("1,500", "KWD", en) }.exceptionOrNull())
        // Too large keeps core's own error, which SplitWords says ("That amount is too large.").
        val big = runCatching { AmountEntry.parse("99999999999999999999", "INR", en) }.exceptionOrNull()
        assertTrue("$big", big is CoreException.Overflow)
        assertEquals("That amount is too large.", SplitWords.message(big as CoreException, "INR"))
        // The prefills: in the phone's mark, and read back on that phone (a KWD "1.005" on a ',' phone would be refused).
        for ((locale, want) in listOf(de to "1,005", en to "1.005")) {
            assertEquals(want, AmountEntry.digits(1_005, "KWD", locale))
            assertEquals(want, OtherAmount.prefill(1_005, "KWD", locale))
            assertEquals(1_005L, AmountEntry.minor(want, "KWD", locale))
        }
        val keystore = MemoryKeystore()
        val path = Files.createTempDirectory("d248-prefill").resolve("ledger-vault.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        store.appendExpense(
            ExpenseDTO(
                payer = me, paidMinor = 1_005, currencyCode = "KWD", group = group,
                shares = listOf(ExpenseDTO.ShareDTO(me, 1_000), ExpenseDTO.ShareDTO(rahul, 5)),
                note = "Tea", timestampMs = 1_000, addedBy = me,
            ),
            nowMs = 5_000,
        )
        val tea = store.projectedExpenses().expenses.first()
        val edit = ExpenseEditingUi.prefill(tea, de)
        assertEquals("1,005", edit.amountText)
        assertEquals(AddExpenseUi.SplitDraft.Exact(mapOf(me to "1,000", rahul to "0,005")), edit.draft)
        assertEquals("Edit saves what it opened with", 1_005L, AmountEntry.parse(edit.amountText, "KWD", de))
        assertEquals("1.005", ExpenseEditingUi.prefill(tea, en).amountText)
        // The example and the prefills are core's strings: nothing in the shell swaps a mark into an amount.
        val entry = src("AddExpenseScreen.kt").substringAfter("object AmountEntry {").substringBefore("\n}\n")
        assertFalse(".replace(" in entry)
        assertTrue("runCatching { ffiMoneyTypedDigits(minor, currency, mark(locale)) }.getOrNull()" in entry)
    }

    /** Every typed-amount site reads through the typed parser; the strict one is left to import and the fixed sample. */
    @Test
    fun every_entry_field_uses_the_typed_parser() {
        for ((name, text) in allSources()) {
            if ("ffiMoneyParseMinor(" in text) assertEquals("$name parses an amount strictly", "AccountHubScreen.kt", name)
        }
        assertTrue("the fixed \"48\", not a typed field", "ffiMoneyParseMinor(WHOLE_AMOUNT, CURRENCY)" in src("AccountHubScreen.kt"))
        val add = src("AddExpenseScreen.kt")
        assertTrue("Save and the live lines", "val total = AmountEntry.parse(amountText, currency)" in add)
        assertTrue("SettleAll", "val minor = AmountEntry.minor(text, currency, locale) ?: return Verdict.NotAnAmount(" in src("SettleAll.kt"))
        assertTrue("RemovalFlow", "val minor = AmountEntry.minor(typed[id] ?: \"\", currency)" in src("RemovalFlow.kt"))
    }

    /** Settle up's Another amount: a decimal comma reads; what is not an amount is said, Settle stays off. */
    @Test
    fun another_amount_says_what_is_not_an_amount() {
        fun verdict(text: String, locale: Locale = en) = OtherAmount.verdict(text, 45_000, "INR", false, "Ana", locale) {}
        assertEquals("a decimal comma", OtherAmount.Verdict.Settles(20_050), verdict("200,50", de))
        assertEquals(OtherAmount.Verdict.Incomplete, verdict("0,00", de))
        for (text in listOf("", "  ", "0", "0.00", "-5")) assertEquals("'$text'", OtherAmount.Verdict.Incomplete, verdict(text))
        // Decision 203: on a '.' phone a comma is never the decimal mark.
        for (text in listOf("abc", "1.2.3", "₹10", "1,000.00", "2,00,000", "200,50", "0,00", "1,500")) {
            assertEquals("'$text'", OtherAmount.Verdict.NotAnAmount("Enter an amount like 12.50."), verdict(text))
        }
        assertEquals(OtherAmount.Verdict.NotAnAmount("Enter an amount like 12,50."), verdict("abc", de))
        assertEquals("ambiguous on a ',' phone", OtherAmount.Verdict.NotAnAmount("Enter an amount like 12,50."), verdict("1.234", de))
        // Review M1: the words wait for the field to rest (or a Settle tap), never on the first keystroke.
        assertEquals("w", OtherAmount.capWords(OtherAmount.Verdict.NotAnAmount("w"), null, rested = true))
        assertNull(OtherAmount.capWords(OtherAmount.Verdict.NotAnAmount("w"), null, rested = false))
        assertEquals("a real amount over the debt says so at once", "t", OtherAmount.capWords(OtherAmount.Verdict.TooMuch("t"), null, rested = false))
        assertFalse(OtherAmount.settles(OtherAmount.Verdict.NotAnAmount("w")))
        assertTrue("a tap says why", OtherAmount.tappable(OtherAmount.Verdict.NotAnAmount("w")))
        assertFalse(OtherAmount.tappable(OtherAmount.Verdict.Incomplete))
        assertFalse(OtherAmount.tappable(OtherAmount.Verdict.TooMuch("t")))
        assertEquals(1_000L, OtherAmount.WORDS_PAUSE_MS)
        val sheet = src("SettleAll.kt")
        assertTrue("the sheet's line", "verdict is Verdict.NotAnAmount && rested -> verdict.words" in sheet)
        assertTrue("OtherAmount.capWords(verdict, refused, rested)?.let { words ->" in sheet)
        assertTrue("PrimaryAction(SettleAllCopy.SETTLE, inDark, enabled = OtherAmount.tappable(verdict) && refused == null) {" in sheet)
        assertTrue("if (verdict is OtherAmount.Verdict.NotAnAmount) rested = true" in sheet)
        assertTrue("LaunchedEffect(text) {\n                rested = false\n                kotlinx.coroutines.delay(OtherAmount.WORDS_PAUSE_MS)\n                rested = true\n            }" in sheet)
    }

    // ---- U30.3 blanks ----

    private val names = mapOf(me to "Asha (You)", rahul to "Rahul")

    private fun spec(draft: AddExpenseUi.SplitDraft, locale: Locale = en): SplitSpecInput =
        AddExpenseUi.buildSpec(draft, listOf(me, rahul), "INR", me, { names[it] ?: "" }, locale)

    /** Tax and tip left blank are 0 (they are optional); a blank exact row at Save is named; an item left blank is 0, so
     *  core's own words say it ("Every item needs an amount above zero."). */
    @Test
    fun blank_tax_and_tip_are_zero_and_a_blank_exact_row_is_named() {
        val itemised = spec(AddExpenseUi.SplitDraft.Itemised(listOf(AddExpenseUi.SplitDraft.LineDraft("900", setOf(me, rahul))), tax = "", tip = "  "))
        assertEquals(SplitSpecInput.Itemised(listOf(LineInput(90_000, listOf(me, rahul))), 0, 0), itemised)
        // The whole itemised expense computes through core with them blank.
        assertEquals(90_000L, ffiSplitCompute(90_000, "INR", listOf(me, rahul), itemised).sumOf { it.minorUnits })
        assertEquals(
            SplitSpecInput.Itemised(listOf(LineInput(90_000, listOf(me))), 1_050, 500),
            spec(AddExpenseUi.SplitDraft.Itemised(listOf(AddExpenseUi.SplitDraft.LineDraft("900", setOf(me))), tax = "10,50", tip = "5"), de),
        )
        // A blank exact row: named at Save; the live line counts it as nothing (liveDraft).
        assertEquals(
            AmountEntry.Refusal("Enter an amount for Rahul."),
            runCatching { spec(AddExpenseUi.SplitDraft.Exact(mapOf(me to "500"))) }.exceptionOrNull(),
        )
        assertEquals(
            SplitSpecInput.Exact(listOf(IdAmount(me, 50_000), IdAmount(rahul, 0))),
            spec(AddExpenseUi.liveDraft(AddExpenseUi.SplitDraft.Exact(mapOf(me to "500")), setOf(me, rahul))),
        )
        assertEquals(
            SplitSpecInput.Exact(listOf(IdAmount(me, 50_025), IdAmount(rahul, 150))),
            spec(AddExpenseUi.SplitDraft.Exact(mapOf(me to "500,25", rahul to "1.5")), de),
        )
        // What is typed and not an amount: AmountEntry's words, never core's raw error.
        assertEquals(
            AmountEntry.Refusal("Enter an amount like 12,50."),
            runCatching { spec(AddExpenseUi.SplitDraft.Exact(mapOf(me to "5x", rahul to "1")), de) }.exceptionOrNull(),
        )
        assertEquals(
            AmountEntry.Refusal("Enter an amount like 12.50."),
            runCatching { spec(AddExpenseUi.SplitDraft.Itemised(listOf(AddExpenseUi.SplitDraft.LineDraft("900", setOf(me))), tax = "1,2,3")) }.exceptionOrNull(),
        )
        // Decision 203: on a '.' phone the same comma is refused in words, never read.
        assertEquals(
            AmountEntry.Refusal("Enter an amount like 12.50."),
            runCatching { spec(AddExpenseUi.SplitDraft.Itemised(listOf(AddExpenseUi.SplitDraft.LineDraft("900", setOf(me))), tax = "10,50")) }.exceptionOrNull(),
        )
        // An adjustment left blank is none; an item left blank is 0, and core says so in words.
        assertEquals(
            SplitSpecInput.Adjustment(listOf(IdAmount(me, -250), IdAmount(rahul, 0))),
            spec(AddExpenseUi.SplitDraft.Adjustment(mapOf(me to "-2,50")), de),
        )
        val blankLine = spec(AddExpenseUi.SplitDraft.Itemised(listOf(AddExpenseUi.SplitDraft.LineDraft("", setOf(me)))))
        val refused = runCatching { ffiSplitCompute(100, "INR", listOf(me, rahul), blankLine) }.exceptionOrNull()
        assertEquals("Every item needs an amount above zero.", SplitWords.message(refused as CoreException, "INR"))
        // The whole save path: a blank exact row is named through buildExpense too, and Save shows a refusal's words.
        val saved = runCatching {
            AddExpenseUi.buildExpense("30.00", "INR", "", null, setOf(me, rahul), AddExpenseUi.SplitDraft.Exact(mapOf(me to "30")), me, group,
                name = { names[it] ?: "" })
        }.exceptionOrNull()
        assertEquals(AmountEntry.Refusal("Enter an amount for Rahul."), saved)
        val add = src("AddExpenseScreen.kt")
        assertTrue("} catch (e: AmountEntry.Refusal) {\n                                e.words" in add)
        assertTrue("name = nameOf," in add)
    }

    // ---- U29.2 Next adds a person row ----

    @Test
    fun next_on_the_last_named_row_adds_a_row_and_an_empty_one_ends_editing() {
        var draft = PeopleDraft()
        val (same, none) = draft.afterReturn(0)
        assertNull("an empty last row ends editing", none)
        assertEquals("and adds nothing", 1, same.rows.size)
        draft = draft.rename(0, "Rahul")
        val (added, next) = draft.afterReturn(0)
        assertEquals("a named last row adds a row and moves to it", 1, next)
        assertEquals(listOf(0, 1), added.rows.map { it.id })
        val (kept, below) = added.afterReturn(0)
        assertEquals("a row above moves down, adding nothing", 1, below)
        assertEquals(2, kept.rows.size)
        assertNull("blank is empty", added.rename(1, "   ").afterReturn(1).second)
        assertNull("a row that has gone", added.afterReturn(42).second)
        assertEquals(added, added.afterReturn(42).first)
        // The screen wires it: Next on every person row, the new row focused once composed, an empty last row closes.
        val create = src("GroupScreens.kt")
        assertTrue("focus = rowFocus.getOrPut(r.id) { FocusRequester() },\n                                onNext = { returned(r.id) }," in create)
        assertTrue("val (next, row) = people.afterReturn(id)" in create)
        assertTrue("runCatching { rowFocus[id]?.requestFocus() }" in create)
        assertTrue("KeyboardActions(onNext = { onNext() })" in create)
        assertTrue("fieldModifier = focus?.let { Modifier.focusRequester(it) } ?: Modifier," in create)
        assertTrue("the field row's own modifier", "modifier = Modifier.weight(1f).then(fieldModifier).semantics { contentDescription = spoken }," in src("ListRows.kt"))
    }

    // ---- U29.3 the amount is focused on a new expense ----

    @Test
    fun the_amount_takes_the_keyboard_on_a_new_expense_only() {
        assertTrue(AmountFocus.focusesOnOpen(editing = false, already = false))
        assertFalse("never on Edit", AmountFocus.focusesOnOpen(editing = true, already = false))
        assertFalse("once (not after a rotation or back from a choice page)", AmountFocus.focusesOnOpen(editing = false, already = true))
        val add = src("AddExpenseScreen.kt")
        assertTrue(".focusRequester(amountFocus)" in add)
        assertTrue("if (AmountFocus.focusesOnOpen(editing = editingId != null, already = focusedOnce)) {" in add)
        assertTrue("the keyboard is shown", "runCatching { amountFocus.requestFocus() }\n            keyboard?.show()" in add)
    }

    // ---- U29.4 the hint ----

    @Test
    fun the_amount_hint_is_ground_faint_at_full_opacity() {
        val add = src("AddExpenseScreen.kt")
        assertTrue("if (amountText.isEmpty()) Text(AmountEntry.HINT, style = big.copy(color = Tok.groundFaint(dark)))" in add)
        assertFalse("no dimmed hint", "Tok.textFaint(dark).copy(alpha = 0.45f)" in add)
        assertEquals("0", AmountEntry.HINT)
    }

    // ---- U30.1 tab-proof words ----

    @Test
    fun no_message_sends_the_person_to_a_tab() {
        var sites = 0
        for ((name, text) in allSources()) {
            assertFalse(name, "on Balance first" in text)
            sites += text.split("\"Add your name first.\"").size - 1
            sites += text.split("\"Create a group first.\"").size - 1
        }
        // Import's two (ImportFlow and its group step), Settle up's two, Add expense's two.
        assertEquals(6, sites)
    }

    // ---- U30.4 the delete's Undo where it happened ----

    @Test
    fun the_delete_undo_is_the_toast_wherever_the_detail_opened() {
        val keystore = MemoryKeystore()
        val path = Files.createTempDirectory("d248-undo").resolve("ledger-vault.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        store.appendExpense(
            ExpenseDTO(
                payer = me, paidMinor = 120_000, currencyCode = "INR", group = group,
                shares = listOf(ExpenseDTO.ShareDTO(me, 70_000), ExpenseDTO.ShareDTO(rahul, 50_000)),
                note = "Dinner", timestampMs = 1_000, category = "food.dining", addedBy = me,
            ),
            nowMs = 5_000,
        )
        val dinner = store.projectedExpenses().expenses.first()
        assertEquals("food.dining", dinner.category)
        store.reviseExpense(ExpenseEditingUi.delete(dinner, me), nowMs = 6_000)
        assertEquals(emptyList<ExpenseDTO>(), store.loadExpenses())

        val center = DoneMomentCenter()
        var written = 0
        ExpenseUndo.offer(
            dinner, path, me, center = center, screenReader = false, store = { store }, written = { written += 1 },
            run = { it() }, clock = { 1_000L },
        )
        assertEquals(DoneMoment.Small(DoneLine("Expense deleted", listOf("Dinner"), "Undo", 5.0)), center.current)
        center.act(center.serial)
        // Review M5: the whole expense comes back as it was (amount, payer, shares, category, date, note), not only its note.
        val restored = store.projectedExpenses().expenses.firstOrNull { it.id == dinner.id }
        assertTrue("Undo restored it", restored != null && !restored.deleted)
        assertEquals(dinner.expense.paidMinor, restored!!.expense.paidMinor)
        assertEquals(dinner.expense.currencyCode, restored.expense.currencyCode)
        assertEquals(dinner.expense.payer, restored.expense.payer)
        assertEquals(dinner.expense.shares, restored.expense.shares)
        assertEquals(dinner.expense.group, restored.expense.group)
        assertEquals(dinner.timestampMs, restored.timestampMs)
        assertEquals(dinner.category, restored.category)
        assertEquals(dinner.note, restored.note)
        assertEquals(1, store.loadExpenses().size)
        assertEquals("every screen reads again", 1, written)
        assertTrue("a written Undo says nothing more", (center.current as? DoneMoment.Small)?.line?.title != DoneCopy.NOT_RESTORED)
        // Review M2: an Undo that cannot write says so ("Not restored."), on the main thread, as a settle's Undo says
        // "Not recorded.". Here the expense is not in this vault, so the store refuses the revision.
        val elsewherePath = Files.createTempDirectory("d248-undo-other").resolve("ledger-vault.db").toString()
        val elsewhere = LedgerStore(elsewherePath) { SkiptiVault.open(elsewherePath, keystore) }
        var onMain = 0
        ExpenseUndo.offer(
            dinner, elsewherePath, me, center = center, screenReader = false, store = { elsewhere }, written = {},
            run = { it() }, clock = { 1_500L }, onMain = { onMain += 1; it() },
        )
        center.act(center.serial)
        assertEquals(1, onMain)
        assertEquals(DoneMoment.Small(DoneLine("Not restored.", emptyList())), center.current)
        assertEquals("Not restored.", DoneCopy.NOT_RESTORED)
        // A screen reader gets the hold's longer window; a note-less expense shows the title alone.
        assertEquals(12.0, (ExpenseUndo.moment(dinner, SettleHold.window(true)) as DoneMoment.Small).line.actionSeconds, 0.0)
        assertEquals(emptyList<String>(), (ExpenseUndo.moment(dinner.copy(note = null), 5.0) as DoneMoment.Small).line.parts)
        // The negative: an Undo after the toast has gone (a newer moment) writes nothing.
        store.reviseExpense(ExpenseEditingUi.delete(store.projectedExpenses().expenses.first(), me), nowMs = 7_000)
        ExpenseUndo.offer(dinner, path, me, center = center, screenReader = false, store = { store }, written = {}, run = { it() }, clock = { 2_000L })
        val stale = center.serial
        center.post(DoneMoments.line(DoneCopy.NAME_SAVED), 2_500L)
        center.act(stale)
        assertEquals("a newer toast: nothing restored", emptyList<ExpenseDTO>(), store.loadExpenses())

        // Every detail page offers it (the group page, Lately, Activity and a push all open ExpenseDetailScreen), and so
        // does the list's long press; the list's foot row and the shell's memory of it have gone.
        val detail = src("ExpenseDetailScreen.kt")
        assertTrue("ExpenseUndo.offer(e, vaultPath, group?.selfId)\n                            onDeleted(e.id)" in detail)
        val list = src("ExpenseListScreen.kt")
        assertTrue("ExpenseUndo.offer(e, vaultPath, groupOf(e)?.selfId)" in list)
        for (gone in listOf("undoable", "justDeleted", "onUndone", "Deleted “")) assertFalse(gone, gone in list)
        assertFalse("justDeleted" in src("AppRoot.kt"))
        assertEquals("Expense deleted", DoneCopy.EXPENSE_DELETED)
    }

    // ---- U30.6 a new group opens ----

    @Test
    fun a_new_group_opens_its_page_over_home() {
        val root = src("AppRoot.kt")
        val create = root.substringAfter("\"new-group\", \"new-group/you\" -> {").substringBefore("        \"your-name\" -> {")
        assertTrue("onCreated = { created ->" in create)
        assertTrue("from Home, Groups and the welcome", "selectedName = HomeTab.BALANCE.name\n                        openGroup = created" in create)
        assertTrue("from You, back to You", "if (fromYou) {\n                        route = \"account\"" in create)
        // Every create path goes through this route: Home's, Groups' and the welcome's Start a group.
        assertEquals("Home's New group, Groups' and the welcome's", 3, root.split("route = \"new-group\"").size - 1)
        val screen = src("GroupScreens.kt")
        assertTrue("fun CreateGroupScreen(vaultPath: String, onCreated: (String) -> Unit," in screen)
        assertTrue("onCreated(created.group.id)" in screen)
        // The first group's big moment is still posted by the screen (the shell's overlay draws it over the page).
        assertTrue("DoneCenter.post(\n                            DoneMoments.groupCreated(" in screen)
    }

    // ---- U31 the inline title cap (decision 186) ----

    private val fonts = java.io.File("src/main/res/font")
    private val slack = 2.0

    /** Space Grotesk ships as one variable file; AWT draws its default instance (wght 300). iOS's Core Text widths of
     *  the 35 titles at 700 (`.drafts/d248/title-probe.tsv`) over AWT's came to at most 1.022 (`Profile` in Vardr Labs;
     *  `.drafts/d248/stage2/probe/`); Cormorant SC Bold, a static file, matched to the hundredth. */
    private val displayWeightBound = 1.03

    /** Every fixed title a pushed screen draws inline, from the app's own words (the twin of iOS's table), plus every
     *  literal a PushedPage, PushedBar, SheetPage or ChoicePage takes in the sources (so a new one is measured). Left out,
     *  as the person's own data the ellipsis is for: a group's, a person's or an expense's name. */
    private fun pushedTitles(): Set<String> {
        val fixed = SettingsSection.entries.map { it.title } + listOf(
            "About", "Licences", YouCopy.TITLE, EraseCopy.TITLE, ActivityFeed.TITLE, ActivityFeed.GROUP_TITLE,
            CurrencyCopy.ALL_CURRENCIES, "Export", GroupPictures.TITLE, "Groups", "People", RolesCopy.GROUP_SETTINGS_TITLE,
            InviteCopy.JOIN_TITLE, ImportCopy.TITLE, "Review", "Profile", PeopleCopy.INVITATIONS, AddPersonCopy.TITLE,
            PhonesCopy.TITLE, PhonesCopy.DELETE_TITLE, RolesCopy.REDISTRIBUTE_TITLE, UsernameCopy.TITLE, UsernameCopy.CHANGE,
            "Expense", "Sign in", CategoryCopy.CATEGORY, CategoryCopy.SUBCATEGORY, AddSheetCopy.SPLIT_TYPE,
            AddSheetCopy.PAID_BY, AddSheetCopy.GROUP,
        )
        val literal = Regex("""\b(?:PushedPage|PushedBar|SheetPage|ChoicePage)\(\s*"([^"$]+)"""")
        // SkiptiBar.kt's own proof titles are deliberately too long (they prove the ellipsis): not app titles.
        val found = allSources().filter { it.first != "SkiptiBar.kt" }
            .flatMap { (_, text) -> literal.findAll(text).map { it.groupValues[1] }.toList() }
        return (fixed + found).toSet()
    }

    /** [title]'s width in dp drawn at [size] dp in [theme]'s display face and tracking (Type.title's), from the bundled
     *  TTFs. */
    private fun titleWidth(title: String, theme: AppTheme, size: Float): Double {
        val face = Type.face(Role.DISPLAY, theme)
        val file = if (face == Face.CORMORANT) "cormorantsc_bold.ttf" else "spacegrotesk.ttf"
        val attributes = mapOf<java.text.AttributedCharacterIterator.Attribute, Any>(
            java.awt.font.TextAttribute.SIZE to size,
            java.awt.font.TextAttribute.KERNING to java.awt.font.TextAttribute.KERNING_ON,
            java.awt.font.TextAttribute.TRACKING to Type.titleTracking(theme).value,
        )
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File(fonts, file)).deriveFont(attributes)
        val styled = java.text.AttributedString(title)
        styled.addAttribute(java.awt.font.TextAttribute.FONT, font)
        val drawn = java.awt.font.TextLayout(styled.iterator, java.awt.font.FontRenderContext(null, true, true)).advance.toDouble()
        return if (face == Face.GROTESK) drawn * displayWeightBound else drawn
    }

    /** Each pushed title fits a 360 dp phone's bar beside the back button in every theme at the cap (the most any font
     *  scale draws), with the slack in hand, and the cap never shrinks a title below the inline 17. */
    @Test
    fun every_pushed_title_fits_beside_the_back_button_at_any_font_scale() {
        val room = BarDecisions.INLINE_TITLE_ROOM_360.toDouble()
        assertEquals("360 - 2 x 8 - 2 x (6 + 48) - 2 x 8", 220.0, room, 0.0)
        assertTrue("never below 17", BarDecisions.INLINE_TITLE_CAP >= TypeScale.inlineTitle)
        val titles = pushedTitles()
        assertTrue("the table is not empty: ${titles.size}", titles.size > 35)
        var measured = 0
        for (theme in AppTheme.entries) for (title in titles) {
            val drawn = titleWidth(title, theme, BarDecisions.INLINE_TITLE_CAP.toFloat())
            assertTrue("$theme '$title' is $drawn wide in $room", drawn <= room - slack)
            measured++
        }
        assertTrue(measured > 100)
        // The range: the phone's size and step +1 unchanged; the largest scale stops at 20, the smallest at 17.
        assertEquals(17f, BarDecisions.inlineTitleDp(17f), 0f)
        assertEquals(17f * 1.12f, BarDecisions.inlineTitleDp(17f * 1.12f), 0.001f)
        assertEquals(20f, BarDecisions.inlineTitleDp(17f * 1.24f), 0f)
        assertEquals(20f, BarDecisions.inlineTitleDp(17f * 2f), 0f)
        assertEquals(17f, BarDecisions.inlineTitleDp(17f * 0.75f), 0f)
        // The negative: uncapped (17 at the 2.0 scale) the longest title does not fit.
        val longest = SettingsSection.PROFILE_ACCOUNT.title
        assertTrue("uncapped", titleWidth(longest, AppTheme.NORSE, 34f) > room)
        // The instrument: Norse's widest title at 17 is iOS's Core Text width (166.889 in the probe).
        assertEquals(166.889, titleWidth(longest, AppTheme.NORSE, 17f), 0.01)
        // The bar draws the capped size, and the room's geometry is the bar's own.
        val bar = src("SkiptiBar.kt")
        assertTrue("BarDecisions.inlineTitleDp(BarDecisions.titleSize(form, collapsed = true).sp.toDp().value).dp.toSp().value" in bar)
        assertTrue("style = Type.title(dark, size)," in bar)
        assertTrue(".padding(start = BarDecisions.controlShift(BarDecisions.BACK_CENTRE_FROM_EDGE).dp)\n            .size(BarDecisions.TOUCH_TARGET.dp)" in bar)
        // Decision 202 (revised, second wave): iOS's twin caps at the same 20, the most iOS 26 draws.
        val ios = java.io.File("../../ios/Skipti/App/ThemeArt.swift").readText()
        assertTrue("static func inlineCap(_ theme: AppTheme) -> CGFloat { 20 }" in ios)
        assertEquals(20, BarDecisions.INLINE_TITLE_CAP)
    }

    /** Decision 202 (revised, second wave): bar text, the inline title and a pill's label, is drawn between 17 and 20 dp
     *  at every font scale the app can reach, iOS 26's own range for bar text (measured on iOS 27: 15 asked at XS draws 17,
     *  22 asked draws 20; iOS asks for the same range, `TitleStyle.barText`). So the twins' titles and pills are the same
     *  size at every step. The negatives: without the clamp the smallest scale draws under 17 and the largest over 20. */
    @Test
    fun bar_text_is_drawn_17_to_20_as_ios_draws_it() {
        val scales = listOf(0.5f, 0.75f, 0.85f, 1.0f, 1.12f, 1.24f, 1.3f, 1.5f, 1.75f, 2.0f, 2.6f)
        for (scale in scales) {
            val title = BarDecisions.inlineTitleDp(TypeScale.inlineTitle * scale)
            val pill = BarDecisions.pillTextDp(BarDecisions.BAR_BUTTON_SIZE * scale)
            for ((what, size) in listOf("title" to title, "pill" to pill)) {
                assertTrue("x$scale $what $size", size >= 17f && size <= 20f)
            }
            // Inside the range the scaled size is drawn as it is (iOS's own scaling there).
            val scaled = 17f * scale
            if (scaled in 17f..20f) assertEquals(scaled, title, 0.0001f)
            assertEquals("the twins' title and pill take the same range", title, pill, 0.0001f)
        }
        assertEquals(17f, BarDecisions.BAR_TEXT_FLOOR, 0f)
        assertEquals(20f, BarDecisions.PILL_TEXT_CAP, 0f)
        assertEquals(17f, BarDecisions.inlineTitleDp(17f * 0.75f), 0f)
        assertEquals(17f, BarDecisions.pillTextDp(17f * 0.75f), 0f)
        assertEquals(20f, BarDecisions.inlineTitleDp(17f * 2f), 0f)
        assertEquals(20f, BarDecisions.pillTextDp(17f * 2f), 0f)
        // The negatives: the scaled size alone leaves the range at both ends.
        assertTrue(17f * 0.75f < 17f && 17f * 2f > 20f)
        // Both bar text sites draw through the clamp; iOS's floor and caps are the same numbers.
        val bar = src("SkiptiBar.kt")
        assertTrue("fun barTextDp(scaledDp: Float, cap: Float): Float = scaledDp.coerceIn(BAR_TEXT_FLOOR, cap)" in bar)
        assertTrue("fun inlineTitleDp(scaledDp: Float): Float = barTextDp(scaledDp, INLINE_TITLE_CAP.toFloat())" in bar)
        assertTrue("fun pillTextDp(scaledDp: Float): Float = barTextDp(scaledDp, PILL_TEXT_CAP)" in bar)
        val ios = java.io.File("../../ios/Skipti/App/ThemeArt.swift").readText()
        assertTrue("static let floor: CGFloat = 17" in ios)
        assertTrue("font.pointSize < TitleWidth.floor ? font.withSize(TitleWidth.floor) : font" in ios)
        assertTrue("return barText(font)" in ios)
    }

    // ---- Review I3: sheet titles beside their pill ----

    /** Every fixed title a sheet's bar draws, with the pill in its corner (null: no pill, the title has the bar): the
     *  twin of iOS's `sheetTitles`. Left out, as the person's own data the ellipsis is for: a group's, a person's or an
     *  expense's name ("Remove {name}"). */
    private val sheetTitles: List<Pair<String, String?>> = listOf(
        "Your name" to "Cancel", "New group" to "Cancel", "Add expense" to "Cancel", "Edit expense" to "Cancel",
        UsernameCopy.TITLE to "Cancel", UsernameCopy.CHANGE to "Cancel", AddPersonCopy.TITLE to BarDecisions.DONE_LABEL,
        "Expense" to "Cancel", "Expense" to BarDecisions.CLOSE_LABEL, InviteCopy.JOIN_TITLE to "Cancel",
        PeopleCopy.INVITATIONS to BarDecisions.CLOSE_LABEL, PhonesCopy.TITLE to BarDecisions.CLOSE_LABEL,
        GroupPictures.TITLE to "Cancel", SettleAllCopy.HOW_MUCH_PAID to null,
    )

    /** [label]'s width in dp drawn at [size] dp in [theme]'s body face (the pill's), from the bundled TTFs. */
    private fun labelWidth(label: String, theme: AppTheme, size: Float): Double {
        val face = Type.face(Role.BODY, theme)
        val file = if (face == Face.GROTESK) "spacegrotesk.ttf" else "ibmplexsans_regular.ttf"
        val attributes = mapOf<java.text.AttributedCharacterIterator.Attribute, Any>(
            java.awt.font.TextAttribute.SIZE to size,
            java.awt.font.TextAttribute.KERNING to java.awt.font.TextAttribute.KERNING_ON,
        )
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File(fonts, file)).deriveFont(attributes)
        val styled = java.text.AttributedString(label)
        styled.addAttribute(java.awt.font.TextAttribute.FONT, font)
        val drawn = java.awt.font.TextLayout(styled.iterator, java.awt.font.FontRenderContext(null, true, true)).advance.toDouble()
        return if (face == Face.GROTESK) drawn * displayWeightBound else drawn
    }

    /** Each sheet title fits beside its pill on a 360 dp phone ([BarDecisions.sheetTitleRoom360], the pill as wide as its
     *  label makes it at that scale) in every theme at every font scale the app can reach (0.75 to 2.0, the system's
     *  scale times SKIPTI's step), with the slack in hand. The negatives: with the pill's label uncapped, or with D-217's
     *  rule (clear of the wider control on BOTH sides), "Pick your username" is cut at the largest scale. */
    @Test
    fun every_sheet_title_clears_its_pill_at_any_font_scale() {
        val scales = listOf(0.75f, 0.85f, 1.0f, 1.12f, 1.24f, 1.3f, 1.5f, 1.75f, 2.0f)
        var measured = 0
        for (theme in AppTheme.entries) for (scale in scales) for ((title, pill) in sheetTitles) {
            val titleSize = BarDecisions.inlineTitleDp(TypeScale.inlineTitle * scale)
            val pillSize = BarDecisions.pillTextDp(BarDecisions.BAR_BUTTON_SIZE * scale)
            val room = pill?.let { BarDecisions.sheetTitleRoom360(labelWidth(it, theme, pillSize).toFloat()).toDouble() }
                ?: (360.0 - 2 * BarDecisions.BAR_SIDE_MARGIN - 2 * BarDecisions.TITLE_GAP)
            val drawn = titleWidth(title, theme, titleSize)
            assertTrue("$theme x$scale '$title' is $drawn wide in $room", drawn <= room - slack)
            measured++
        }
        assertEquals(AppTheme.entries.size * scales.size * sheetTitles.size, measured)
        // The room is the bar's own geometry: 360 - 2 x 8 margins - the pill's node (label + 2 x 16 + 2 x 2) - 2 x 8 gaps.
        assertEquals(360f - 16f - (60f + 36f) - 16f, BarDecisions.sheetTitleRoom360(60f), 0f)
        assertEquals(20f, BarDecisions.pillTextDp(17f * 2f), 0f)
        assertEquals(17f, BarDecisions.pillTextDp(17f), 0f)
        val longest = UsernameCopy.TITLE
        val atCap = titleWidth(longest, AppTheme.NORSE, BarDecisions.INLINE_TITLE_CAP.toFloat())
        val uncappedPill = labelWidth("Cancel", AppTheme.NORSE, 17f * 2f).toFloat()
        assertTrue("uncapped pill", atCap > BarDecisions.sheetTitleRoom360(uncappedPill) - slack)
        val cappedPill = labelWidth("Cancel", AppTheme.NORSE, BarDecisions.PILL_TEXT_CAP).toFloat()
        val bothSides = (360 - 2 * BarDecisions.BAR_SIDE_MARGIN) - 2 * BarDecisions.pillNodeWidth(cappedPill) - 2 * BarDecisions.TITLE_GAP
        assertTrue("D-217's both-sides rule", atCap > bothSides - slack)
        // The bar draws the capped pill label, and places the title by the measured rule.
        val bar = src("SkiptiBar.kt")
        assertTrue("BarDecisions.pillTextDp(BarDecisions.BAR_BUTTON_SIZE.sp.toDp().value).dp.toSp()" in bar)
        assertTrue(".padding(horizontal = BarDecisions.PILL_TARGET_INSET.dp)" in bar)
        assertTrue("t?.placeRelative(BarDecisions.titleLeft(width, t.width, s?.width ?: 0, e?.width ?: 0, gap)" in bar)
        // iOS's bar buttons stop at the same 20.
        val ios = java.io.File("../../ios/Skipti/App/ThemeArt.swift").readText()
        assertTrue("static let barButtonCap: CGFloat = 20" in ios)
    }

    @Test
    fun the_typed_parser_is_never_float_math() {
        // ADR-0002 in the shell: AmountEntry hands text and the phone's mark to core, and takes core's strings back.
        val entry = src("AddExpenseScreen.kt").substringAfter("object AmountEntry {").substringBefore("\n}\n")
        for (bad in listOf("toDouble", "toFloat", "BigDecimal", "Double(", "Float(")) assertFalse(bad, bad in entry)
        if ("ffiMoneyParseTyped(text, currency, mark(locale))" !in entry) fail("core reads the text")
    }
}
