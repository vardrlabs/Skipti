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

    /** Core's typed parser through the bindings: the same subset of `.drafts/d248/typed_goldens.py`'s table iOS pins
     *  (derived there with exact Fractions, never read out of core). */
    @Test
    fun typed_amounts_read_one_comma_as_the_decimal_mark() {
        val goldens = listOf(
            Triple("12,50", "EUR", 1_250L), Triple(",5", "EUR", 50L), Triple("1,005", "KWD", 1_005L), Triple(" 7,10 ", "USD", 710L),
            Triple("12.50", "EUR", 1_250L), Triple("1005", "JPY", 1_005L),
            Triple("1,234,567", "INR", null), Triple("1,234.56", "USD", null), Triple("1.234,56", "EUR", null),
            Triple("12,505", "EUR", null), Triple("1,0005", "KWD", null), Triple("10,5", "JPY", null), Triple("1,234", "INR", null),
            Triple(",", "EUR", null), Triple("", "EUR", null),
        )
        for ((text, code, want) in goldens) {
            assertEquals("$code '$text'", want, runCatching { ffiMoneyParseTyped(text, code) }.getOrNull())
            assertEquals("$code '$text'", want, AmountEntry.minor(text, code))
        }
        // Import's strict parser is untouched: a comma never reads there.
        assertTrue(runCatching { ffiMoneyParseMinor("12,50", "EUR") }.isFailure)
    }

    /** "Enter an amount like {example}.": the example in the currency's own decimals (core's entry form of 1,250 minor
     *  units) and the phone's decimal mark. */
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
        assertNull("no example is made up", AmountEntry.example("XYZ", en))
        assertEquals("Enter an amount.", AmountEntry.likeWords("XYZ", en))
        assertEquals(1_250L, AmountEntry.parse("12,50", "EUR", de))
        assertEquals(AmountEntry.Refusal("Enter an amount like 12,50."), runCatching { AmountEntry.parse("12,5,0", "EUR", de) }.exceptionOrNull())
        assertEquals(AmountEntry.Refusal("Enter an amount like 1250."), runCatching { AmountEntry.parse("10,5", "JPY", en) }.exceptionOrNull())
        // Too large keeps core's own error, which SplitWords says ("That amount is too large.").
        val big = runCatching { AmountEntry.parse("99999999999999999999", "INR", en) }.exceptionOrNull()
        assertTrue("$big", big is CoreException.Overflow)
        assertEquals("That amount is too large.", SplitWords.message(big as CoreException, "INR"))
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
        assertTrue("SettleAll", "val minor = AmountEntry.minor(text, currency) ?: return Verdict.NotAnAmount(" in src("SettleAll.kt"))
        assertTrue("RemovalFlow", "val minor = AmountEntry.minor(typed[id] ?: \"\", currency)" in src("RemovalFlow.kt"))
    }

    /** Settle up's Another amount: a decimal comma reads; what is not an amount is said, Settle stays off. */
    @Test
    fun another_amount_says_what_is_not_an_amount() {
        fun verdict(text: String, locale: Locale = en) = OtherAmount.verdict(text, 45_000, "INR", false, "Ana", locale) {}
        assertEquals("a decimal comma", OtherAmount.Verdict.Settles(20_050), verdict("200,50"))
        for (text in listOf("", "  ", "0", "0.00", "0,00", "-5")) assertEquals("'$text'", OtherAmount.Verdict.Incomplete, verdict(text))
        for (text in listOf("abc", "1.2.3", "₹10", "1,000.00", "2,00,000")) {
            assertEquals("'$text'", OtherAmount.Verdict.NotAnAmount("Enter an amount like 12.50."), verdict(text))
        }
        assertEquals(OtherAmount.Verdict.NotAnAmount("Enter an amount like 12,50."), verdict("abc", de))
        assertEquals("w", OtherAmount.capWords(OtherAmount.Verdict.NotAnAmount("w"), null))
        assertFalse(OtherAmount.settles(OtherAmount.Verdict.NotAnAmount("w")))
        assertTrue("the sheet's tap", "is OtherAmount.Verdict.NotAnAmount -> verdict.words" in src("SettleAll.kt"))
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
            spec(AddExpenseUi.SplitDraft.Itemised(listOf(AddExpenseUi.SplitDraft.LineDraft("900", setOf(me))), tax = "10,50", tip = "5")),
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
            spec(AddExpenseUi.SplitDraft.Exact(mapOf(me to "500,25", rahul to "1.5"))),
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
        // An adjustment left blank is none; an item left blank is 0, and core says so in words.
        assertEquals(
            SplitSpecInput.Adjustment(listOf(IdAmount(me, -250), IdAmount(rahul, 0))),
            spec(AddExpenseUi.SplitDraft.Adjustment(mapOf(me to "-2,50"))),
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
                shares = listOf(ExpenseDTO.ShareDTO(me, 60_000), ExpenseDTO.ShareDTO(rahul, 60_000)),
                note = "Dinner", timestampMs = 1_000, addedBy = me,
            ),
            nowMs = 5_000,
        )
        val dinner = store.projectedExpenses().expenses.first()
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
        assertEquals("Undo restored it", listOf("Dinner"), store.loadExpenses().map { it.note })
        assertEquals("every screen reads again", 1, written)
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
        // The cap: at the phone's size and up to step +2 nothing changes; the largest scale stops at it.
        assertEquals(17f, BarDecisions.inlineTitleDp(17f), 0f)
        assertEquals(17f * 1.24f, BarDecisions.inlineTitleDp(17f * 1.24f), 0.001f)
        assertEquals(22f, BarDecisions.inlineTitleDp(17f * 2f), 0f)
        // The negatives: uncapped (17 at the 2.0 scale) the longest title does not fit, and the next size up from the cap
        // leaves less than the slack in Norse.
        val longest = SettingsSection.PROFILE_ACCOUNT.title
        assertTrue("uncapped", titleWidth(longest, AppTheme.NORSE, 34f) > room)
        assertTrue("23 is too big here", titleWidth(longest, AppTheme.NORSE, BarDecisions.INLINE_TITLE_CAP + 1f) > room - slack)
        // The instrument: Norse's widest title at 17 is iOS's Core Text width (166.889 in the probe).
        assertEquals(166.889, titleWidth(longest, AppTheme.NORSE, 17f), 0.01)
        // The bar draws the capped size, and the room's geometry is the bar's own.
        val bar = src("SkiptiBar.kt")
        assertTrue("BarDecisions.inlineTitleDp(BarDecisions.titleSize(form, collapsed = true).sp.toDp().value).dp.toSp().value" in bar)
        assertTrue("style = Type.title(dark, size)," in bar)
        assertTrue(".padding(start = BarDecisions.controlShift(BarDecisions.BACK_CENTRE_FROM_EDGE).dp)\n            .size(BarDecisions.TOUCH_TARGET.dp)" in bar)
        // iOS's twin caps at 23 on its 375 pt phone.
        val ios = java.io.File("../../ios/Skipti/App/ThemeArt.swift").readText()
        assertTrue("static func inlineCap(_ theme: AppTheme) -> CGFloat { 23 }" in ios)
    }

    @Test
    fun the_typed_parser_is_never_float_math() {
        // ADR-0002 in the shell: AmountEntry hands text to core and only swaps a mark in an example.
        val entry = src("AddExpenseScreen.kt").substringAfter("object AmountEntry {").substringBefore("\n}\n")
        for (bad in listOf("toDouble", "toFloat", "BigDecimal", "Double(", "Float(")) assertFalse(bad, bad in entry)
        if ("ffiMoneyParseTyped(text, currency)" !in entry) fail("core reads the text")
    }
}
