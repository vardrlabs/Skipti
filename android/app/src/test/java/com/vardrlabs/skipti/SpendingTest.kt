package com.vardrlabs.skipti

import android.content.SharedPreferences
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.ExpenseShare
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.PortExpense
import uniffi.skipti_core.PortGroup
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.SpendingGroupIn
import uniffi.skipti_core.SpendingView
import uniffi.skipti_core.SplitSpecInput
import uniffi.skipti_core.ffiCheckExpense
import uniffi.skipti_core.ffiSnapshotPersonal
import uniffi.skipti_core.ffiSplitCompute

/** D-249 (U10): Just me and All my spending on Android, the twin of iOS's SpendingTests. Core's money goldens are core's
 *  (`.drafts/d248/goldens.py`, pinned in core's own tests); these pin the shell's half: the month bounds it passes, the
 *  words (compared with Spending.swift's), the local switches, Add expense's Just me record, the export's name, the
 *  Wallet import into Just me, the Ledger page's meta, decision 205's per-group "you" and decision 206's start. */
class SpendingTest {
    private fun tempStore(): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = Files.createTempDirectory("spending-test").resolve("v.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    private fun main(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
    private fun swift(name: String) = java.io.File("../../ios/Skipti/App/$name").readText()
    private fun mainSources(): Map<String, String> =
        java.io.File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to it.readText() }

    /** A SharedPreferences held in memory (the JVM has no Android preferences). */
    private class MapPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as String?) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as Int?) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as Long?) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as Float?) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as Boolean?) ?: defValue
        override fun contains(key: String?): Boolean = key in map
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private fun put(key: String?, value: Any?): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor { pending.remove(key); map.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { map.putAll(pending) }
        }
    }

    // ---- The month the shell passes core ----

    /** The phone's month bounds in ms, [first instant, next month's first instant). Goldens from Python's zoneinfo
     *  (`.drafts/d248/goldens.py` for Kolkata; Los Angeles by the same method), the numbers iOS's test pins: the 23:30
     *  expense on 31 October (1793469600000) is inside October, 00:00 on 1 November (1793471400000) is not. */
    @Test
    fun month_bounds_are_the_phones_own_midnights() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val october = SpendingMonth(2026, 10)
        assertEquals(1_790_793_000_000uL, october.bounds(kolkata).fromMs)
        assertEquals(1_793_471_400_000uL, october.bounds(kolkata).untilMs)
        assertEquals("23:30 on the 31st", october, SpendingMonth.containing(1_793_469_600_000, kolkata))
        assertEquals("00:00 on the 1st is the next month", SpendingMonth(2026, 11), SpendingMonth.containing(1_793_471_400_000, kolkata))
        val la = ZoneId.of("America/Los_Angeles")
        assertEquals(1_790_838_000_000uL, october.bounds(la).fromMs)
        assertEquals("across the year", 1_798_790_400_000uL, SpendingMonth(2026, 12).bounds(la).untilMs)
        // The negative: the same month in another zone is another window.
        assertNotEquals(october.bounds(la).fromMs, october.bounds(kolkata).fromMs)
    }

    @Test
    fun months_step_and_read_in_words() {
        val october = SpendingMonth(2026, 10)
        assertEquals("October 2026", october.label)
        assertEquals("October", october.name)
        assertEquals(SpendingMonth(2027, 1), october.adding(3))
        assertEquals(SpendingMonth(2025, 12), october.adding(-10))
        assertEquals("December 2025", SpendingMonth(2026, 1).adding(-1).label)
        assertTrue(october < october.adding(1))
        assertFalse(october < october)
    }

    // ---- The words ----

    @Test
    fun the_words_are_the_approved_ones() {
        assertEquals("Your spending", SpendingCopy.TITLE)
        assertEquals("+1 more", SpendingCopy.more(1))
        assertEquals("Your spending, Just me, four rupees this month", SpendingCopy.spoken("Just me", "four rupees"))
        assertEquals("Your spending, All my spending, Nothing yet this month", SpendingCopy.spoken("All my spending", null))
        assertEquals("Goa trip, your share", SpendingCopy.groupMeta("Goa trip"))
        assertEquals(listOf("Category", "Spent", "Share"), SpendingCopy.COLUMNS)
        assertFalse("decision 188: no sync word", SpendingCopy.EMPTY_DETAIL.lowercase().contains("sync"))
        assertEquals("Nothing in October", SpendingCopy.emptyMonth("October"))
        assertEquals("Since October 2026", SpendingCopy.since("October 2026"))
        assertEquals("spent ₹381.00", SpendingCopy.spentAmount("₹381.00"))
        assertEquals("Just me", SpendingView.JUST_ME.label)
        assertEquals("All my spending", SpendingView.ALL.label)
        assertEquals("Ledger", AddSheetCopy.LEDGER)
        assertEquals("Just me", ExpenseCopy.JUST_ME)
        assertEquals("Start a group for a trip, a flat or a dinner, or keep your own spending in Just me.", WelcomeCopy.DONE_LINE)
    }

    /** The parity table: every string constant of iOS's `SpendingCopy` and `NoGroupHeroCopy` has its Kotlin twin with the
     *  same words, and iOS has none this table does not list (a new word on one phone fails here). */
    @Test
    fun the_words_match_spending_swift() {
        val swift = swift("Spending.swift")
        fun enumBody(name: String): String {
            val start = swift.indexOf("enum $name {")
            assertTrue("Spending.swift has $name", start >= 0)
            return swift.substring(start, swift.indexOf("\n}\n", start))
        }
        val copy = mapOf(
            "title" to SpendingCopy.TITLE, "justMe" to SpendingCopy.JUST_ME, "all" to SpendingCopy.ALL,
            "thisMonth" to SpendingCopy.THIS_MONTH, "empty" to SpendingCopy.EMPTY, "hint" to SpendingCopy.HINT,
            "allNote" to SpendingCopy.ALL_NOTE, "prev" to SpendingCopy.PREV, "next" to SpendingCopy.NEXT,
            "spent" to SpendingCopy.SPENT, "chart" to SpendingCopy.CHART, "table" to SpendingCopy.TABLE,
            "expenses" to SpendingCopy.EXPENSES, "emptyTitle" to SpendingCopy.EMPTY_TITLE,
            "emptyDetail" to SpendingCopy.EMPTY_DETAIL, "export" to SpendingCopy.EXPORT, "homeRow" to SpendingCopy.HOME_ROW,
            "homeSummary" to SpendingCopy.HOME_SUMMARY, "onlyYouNothingToSplit" to SpendingCopy.ONLY_YOU_NOTHING_TO_SPLIT,
            "onlyYou" to SpendingCopy.ONLY_YOU, "deleteMessage" to SpendingCopy.DELETE_MESSAGE,
            "importIntoJustMe" to SpendingCopy.IMPORT_INTO_JUST_ME, "importAsGroup" to SpendingCopy.IMPORT_AS_GROUP,
            "unread" to SpendingCopy.UNREAD,
        )
        val hero = mapOf(
            "kicker" to NoGroupHeroCopy.KICKER, "title" to NoGroupHeroCopy.TITLE, "line" to NoGroupHeroCopy.LINE,
            "create" to NoGroupHeroCopy.CREATE, "join" to NoGroupHeroCopy.JOIN, "importLink" to NoGroupHeroCopy.IMPORT,
        )
        val literal = Regex("""static let (\w+) = "([^"]*)"""")
        for ((name, table) in listOf("SpendingCopy" to copy, "NoGroupHeroCopy" to hero)) {
            val found = literal.findAll(enumBody(name)).associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals("$name: the same words, each with its twin", table, found)
        }
        assertTrue("static let columns = [\"Category\", \"Spent\", \"Share\"]" in swift)
        assertTrue("static func groupMeta(_ group: String) -> String { \"\\(group), your share\" }" in swift)
        assertTrue("static func spentAmount(_ amount: String) -> String { \"spent \\(amount)\" }" in swift)
        assertTrue("static func since(_ monthYear: String) -> String { \"Since \\(monthYear)\" }" in swift)
        assertTrue("static func emptyMonth(_ month: String) -> String { \"Nothing in \\(month)\" }" in swift)
        assertTrue("static let ledger = \"${AddSheetCopy.LEDGER}\"" in swift("AddExpenseScreen.swift"))
        assertTrue("static let doneLine = \"${WelcomeCopy.DONE_LINE}\"" in swift("Welcome.swift"))
        assertTrue("static let groupMessage = \"${ExpenseDeleteCopy.GROUP_MESSAGE}\"" in swift("ExpenseDetailView.swift"))
        assertTrue("static let you = \"${ExpenseCopy.YOU}\"" in swift("ExpenseListView.swift"))
        // The month names are the same fixed English on both phones.
        assertTrue(SpendingMonth.NAMES.joinToString("\", \"", "[\"", "\"]").replace("\"October\", ", "\"October\",\n                        ") in swift)
    }

    /** The deleted strings are gone from the app (plan section 3): the two "belong to a group" lines, the Expenses tab's
     *  "Personal", Home's old empty state, Wallet's "in a new group with just you". Scanned in every app source. */
    @Test
    fun the_deleted_strings_are_gone() {
        val sources = mainSources()
        assertTrue(sources.size > 40)
        for (gone in listOf(
            "Expenses belong to a group", "You can't add to your groups from this phone", "const val PERSONAL = \"Personal\"",
            "Create a group, join one you were invited to, or import from another app.", "in a new group with just you",
        )) {
            // The fixture BalanceScreen (a launch-arg proof surface, never a person's Home) keeps its own empty words.
            for ((file, text) in sources) {
                if (file == "BalanceScreen.kt" && gone.startsWith("Create a group, join")) continue
                assertFalse("$file still says $gone", gone in text)
            }
        }
    }

    // ---- Home ----

    @Test
    fun the_hero_asks_only_with_no_group() {
        assertEquals(HomeHero.NO_GROUP, HomeHero.kind(0))
        assertEquals(HomeHero.BALANCE, HomeHero.kind(1))
        assertEquals(HomeHero.BALANCE, HomeHero.kind(4))
    }

    /** Spending on Home and the switch are this phone's (decision 189): on and Just me until changed, remembered, and
     *  never in the synced settings' table. */
    @Test
    fun the_switches_are_remembered_on_this_phone_only() {
        val prefs = MapPrefs()
        assertEquals("Just me until chosen", SpendingView.JUST_ME, SpendingPrefs.readView(prefs))
        assertTrue("on until turned off", SpendingPrefs.readOnHome(prefs))
        SpendingPrefs.write(SpendingView.ALL, prefs)
        assertEquals(SpendingView.ALL, SpendingPrefs.readView(prefs))
        assertEquals("all", prefs.map[SpendingPrefs.VIEW_KEY])
        SpendingPrefs.write(SpendingView.JUST_ME, prefs)
        assertEquals(SpendingView.JUST_ME, SpendingPrefs.readView(prefs))
        SpendingPrefs.writeOnHome(false, prefs)
        assertFalse("off hides the card", SpendingPrefs.readOnHome(prefs))
        assertEquals(SpendingView.JUST_ME, SpendingPrefs.view("anything else"))
        // iOS's keys, its own preferences file, never a synced one.
        assertTrue("static let viewKey = \"${SpendingPrefs.VIEW_KEY}\"" in swift("Spending.swift"))
        assertTrue("static let onHomeKey = \"${SpendingPrefs.ON_HOME_KEY}\"" in swift("Spending.swift"))
        for (s in SettingsSync.synced) {
            assertNotEquals(SpendingPrefs.PREFS, s.file)
            assertFalse(s.key.startsWith("spending."))
        }
        // Spending on Home writes SpendingPrefs itself, never through SettingsSync.
        val hub = main("AccountHubScreen.kt")
        val row = hub.substring(hub.indexOf("private fun SpendingHomeRow(dark: Boolean) {")).take(900)
        assertTrue("SpendingPrefs.setOnHome(context, on)" in row)
        assertTrue("summaryLines = 2" in row)
        assertFalse("SettingsSync" in row)
        assertTrue("after Less motion", hub.indexOf("MotionRow(dark)\n") < hub.indexOf("SpendingHomeRow(dark)\n"))
    }

    /** Decision 206: the page opened from the Expenses tab's Just me header always starts in Just me, whatever is
     *  remembered, and opening it writes nothing (the card keeps its view); from the card it opens in the remembered one. */
    @Test
    fun the_page_from_the_just_me_header_starts_in_just_me() {
        assertEquals(SpendingView.JUST_ME, SpendingPrefs.pageStart(fromJustMeHeader = true, stored = SpendingView.ALL))
        assertEquals(SpendingView.JUST_ME, SpendingPrefs.pageStart(fromJustMeHeader = true, stored = SpendingView.JUST_ME))
        // Negative: from the card the remembered All stays All.
        assertEquals(SpendingView.ALL, SpendingPrefs.pageStart(fromJustMeHeader = false, stored = SpendingView.ALL))
        val shell = main("AppRoot.kt")
        assertTrue("onOpenSpending = { spendingOpen = SpendingOpen.HEADER }" in shell)
        assertTrue("onOpenSpending = { spendingOpen = SpendingOpen.CARD }" in shell)
        assertTrue(Regex("""page\.spending == SpendingOpen\.HEADER\) \{[^}]*SpendingScreen\(\s*vaultPath = vaultPath, dark = dark, fromJustMeHeader = true,""").containsMatchIn(shell))
        assertTrue(Regex("""page\.spending == SpendingOpen\.CARD\) \{[^}]*SpendingScreen\(\s*vaultPath = vaultPath, dark = dark, fromJustMeHeader = false,""").containsMatchIn(shell))
        // The page reads the start without writing it; only a tap on the switch writes.
        val page = main("Spending.kt")
        assertTrue("val view = opened ?: SpendingPrefs.pageStart(fromJustMeHeader, SpendingPrefs.shownView)" in page)
        assertTrue("        opened = v\n        SpendingPrefs.setView(context, v)" in page)
        // The + over the page opens Add expense on Just me.
        assertTrue("onAdd = { route = if (SpendingPresence.shown) \"add/justme\" else \"add\" }" in shell)
        assertTrue("startOnJustMe = shown == \"add/justme\"" in shell)
        assertTrue(SpendingOpen.covers(SpendingOpen.CARD, HomeTab.BALANCE))
        assertTrue(SpendingOpen.covers(SpendingOpen.HEADER, HomeTab.EXPENSES))
        assertFalse(SpendingOpen.covers(SpendingOpen.HEADER, HomeTab.BALANCE))
        assertFalse(SpendingOpen.covers(null, HomeTab.BALANCE))
    }

    /** The card is drawn only while Spending on Home is on, and after the ledgers; the no-group hero replaced the old
     *  empty state. */
    @Test
    fun home_draws_the_card_under_the_switch_and_the_hero_with_no_group() {
        val home = main("OverviewTab.kt")
        assertTrue("if (SpendingPrefs.shownOnHome) {\n                    SectionLabel(SpendingCopy.TITLE" in home)
        val ledgers = home.indexOf("SectionLabel(LedgerCopy.TITLE")
        val spending = home.indexOf("SectionLabel(SpendingCopy.TITLE")
        assertTrue("Your spending sits under Your ledgers", ledgers in 0 until spending)
        assertTrue("if (error == null && HomeHero.kind(groups.size) == HomeHero.NO_GROUP) {\n                    NoGroupHero(" in home)
        assertFalse("loaded && groups.isEmpty() && error == null ->" in home)
    }

    // ---- Add expense, Just me ----

    @Test
    fun the_ledger_row_and_its_defaults() {
        assertEquals("Just me plus one group is a choice", AddExpenseRows.LedgerRow.CHOOSER, AddExpenseRows.ledgerRow(false, 1))
        assertEquals(AddExpenseRows.LedgerRow.CHOOSER, AddExpenseRows.ledgerRow(false, 3))
        assertEquals("Just me alone", AddExpenseRows.LedgerRow.PLAIN, AddExpenseRows.ledgerRow(false, 0))
        assertEquals("an edit never moves ledgers", AddExpenseRows.LedgerRow.PLAIN, AddExpenseRows.ledgerRow(true, 3))
        assertTrue("no group: Just me", AddExpenseRows.startsOnJustMe(fromSpending = false, writableCount = 0))
        assertTrue("from Your spending: Just me", AddExpenseRows.startsOnJustMe(fromSpending = true, writableCount = 2))
        assertFalse("the shown group, as before", AddExpenseRows.startsOnJustMe(fromSpending = false, writableCount = 2))
        assertFalse("Just me hides the split", AddExpenseRows.showsSplit(justMe = true))
        assertTrue(AddExpenseRows.showsSplit(justMe = false))
        val screen = main("AddExpenseScreen.kt")
        assertTrue("if (AddExpenseRows.showsSplit(justMe)) SplitSection(" in screen)
        assertTrue("SplitSection draws the split", "private fun SplitSection(" in screen)
        assertEquals("the split's label only inside it", 1, screen.split("SectionLabel(AddSheetCopy.SPLIT, dark)").size - 1)
        assertTrue(screen.indexOf("private fun SplitSection(") < screen.indexOf("SectionLabel(AddSheetCopy.SPLIT, dark)"))
        assertTrue("if (justMe) CardCaption(SpendingCopy.ONLY_YOU_NOTHING_TO_SPLIT, dark)" in screen)
    }

    /** Add expense with Just me writes a personal-scope Expense record: no group, you paid, shares [you: total], checked
     *  by core first (a refused amount writes nothing). */
    @Test
    fun a_just_me_expense_is_a_personal_record_of_yours_alone() {
        val store = tempStore()
        val me = store.ensureSelf()
        val dto = JustMeExpense.dto(me.id, 124_000, "INR", "Big Basket order", null, nowMs = 1_791_174_600_000)
        assertNull(dto.group)
        assertEquals(me.id, dto.payer)
        assertEquals(listOf(ExpenseDTO.ShareDTO(me.id, 124_000)), dto.shares)
        assertEquals(me.id, dto.addedBy)
        store.appendExpense(dto)
        val records = store.allRecords().filter { it.recordType == 1.toUByte() }
        assertEquals(1, records.size)
        assertNull("the personal scope", records[0].groupId)
        val projected = store.projectedExpenses().expenses.single()
        assertNull(projected.expense.group)
        assertEquals(listOf(IdAmount(me.id, 124_000)), projected.expense.shares)
        // The negative: core refuses a negative amount before anything is written.
        try {
            store.appendExpense(JustMeExpense.dto(me.id, -1, "INR", null, null))
            fail("a negative Just me expense was written")
        } catch (_: Exception) {
        }
        assertEquals(1, store.allRecords().count { it.recordType == 1.toUByte() })
        // An edit's money keeps the stored payer as the whole share, and no group.
        val money = JustMeExpense.money(me.id, 9_900, "INR")
        assertNull(money.group)
        assertEquals(listOf(IdAmount(me.id, 9_900)), money.shares)
        ffiCheckExpense(money)
    }

    /** Your spending reads the store through core: Just me counts in full, All adds only your share of a group expense
     *  (you paid ₹900.00 for two: ₹450.00 counts). Totals worked by hand: 1,240.00 and 1,240.00 + 450.00. */
    @Test
    fun your_spending_reads_just_me_and_your_share() {
        val store = tempStore()
        store.setYourName("Sam")
        val me = store.ensureSelf()
        val now = System.currentTimeMillis()
        store.appendExpense(JustMeExpense.dto(me.id, 124_000, "INR", "Groceries", null, nowMs = now))
        val group = store.createGroup("Flat 4B", "INR", listOf("Meera"))
        val shares = ffiSplitCompute(90_000, "INR", group.group.members, SplitSpecInput.Equal)
        store.appendExpense(
            ExpenseDTO(
                payer = group.selfId, paidMinor = 90_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = shares.map { ExpenseDTO.ShareDTO(it.id, it.minorUnits) }, note = "Weekly groceries",
                timestampMs = now, category = null, addedBy = group.selfId,
            ),
        )
        val month = SpendingMonth.current()
        val justMe = SpendingLoad.load(store, month, SpendingView.JUST_ME)
        assertEquals(listOf(124_000L), justMe.out.currencies.map { it.totalMinor })
        assertEquals(1, justMe.out.rows.size)
        assertTrue(justMe.hasAny)
        val all = SpendingLoad.load(store, month, SpendingView.ALL)
        assertEquals(listOf(169_000L), all.out.currencies.map { it.totalMinor })
        assertEquals(setOf(124_000L, 45_000L), all.out.rows.map { it.amountMinor }.toSet())
        assertEquals("Flat 4B", all.groupNames[group.group.id])
        // Another month is empty, and says so as a month (there is spending elsewhere).
        val last = SpendingLoad.load(store, month.adding(-1), SpendingView.JUST_ME)
        assertTrue(last.out.rows.isEmpty())
        assertTrue(last.hasAny)
        // A store with nothing at all: "Nothing here yet".
        assertFalse(SpendingLoad.load(tempStore(), month, SpendingView.JUST_ME).hasAny)
    }

    /** Decision 205: each group is read with its own "you", never a list of your ids matched in every group (an id that
     *  is you in G can be someone else in H: the D-237 A1 shape). Core's golden pins the counting. */
    @Test
    fun each_group_keeps_its_own_you() {
        val g = NamedGroupWire(GroupWire("g", "G", "INR", listOf("a", "c")), "a", emptyList())
        val h = NamedGroupWire(GroupWire("h", "H", "INR", listOf("a", "d")), "d", emptyList())
        assertEquals(listOf(SpendingGroupIn("g", "a"), SpendingGroupIn("h", "d")), SpendingLoad.groups(listOf(g, h)))
        // Negative: "a" is not you in H, and a group listed twice keeps its first "you" (core refuses a repeat).
        assertFalse(SpendingGroupIn("h", "a") in SpendingLoad.groups(listOf(g, h)))
        assertEquals(1, SpendingLoad.groups(listOf(g, g)).size)
        assertEquals(emptyList<SpendingGroupIn>(), SpendingLoad.groups(emptyList()))
        assertTrue("static func groups(named: [NamedGroupWire]) -> [SpendingGroupIn] {" in swift("Spending.swift"))
    }

    // ---- The Ledger page (decision 193) ----

    @Test
    fun the_ledger_rows_carry_their_meta() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(1_791_174_600_000L, LedgerMeta.sinceMs("1791174600000:3:0011223344556677"))
        assertNull(LedgerMeta.sinceMs("not a stamp"))
        assertEquals(listOf("Since October 2026", "3 people"), LedgerMeta.groupParts(1_791_174_600_000, 3, kolkata))
        assertEquals(listOf("1 person"), LedgerMeta.groupParts(null, 1, kolkata))
        assertEquals(listOf("Only you"), LedgerMeta.JUST_ME_PARTS)
        val g = NamedGroupWire(GroupWire("g", "Goa trip", "INR", listOf("a", "b")), "a", emptyList())
        val options = LedgerOptions.build(listOf(g), mapOf("g" to "1793469600000:0:aa"), kolkata)
        assertEquals("Just me first", listOf("Just me", "Goa trip"), options.map { it.title })
        assertEquals(listOf(LedgerChoice.JustMe, LedgerChoice.Group("g")), options.map { it.choice })
        assertEquals(listOf("Since October 2026", "2 people"), options[1].parts)
        assertEquals(listOf("Only you"), options[0].parts)
    }

    // ---- Export Just me ----

    @Test
    fun the_just_me_export_is_named_by_the_phones_day() {
        val date = ZonedDateTime.of(2026, 10, 1, 6, 12, 0, 0, ZoneOffset.UTC)
        assertEquals("skipti-just-me-2026-10-01.csv", Exporter.justMeName(date, Exporter.Format.CSV))
        assertEquals("skipti-just-me-2026-10-01.json", Exporter.justMeName(date, Exporter.Format.JSON))
        // Decision 207: the phone's own date, never UTC's. 19:00Z on 31 October is 1 November at 00:30 in Kolkata and
        // still 31 October in Los Angeles: one instant, each phone's own day.
        val instant = java.time.Instant.ofEpochSecond(1_793_473_200)
        assertEquals("skipti-just-me-2026-11-01.csv", Exporter.justMeName(instant.atZone(ZoneId.of("Asia/Kolkata")), Exporter.Format.CSV))
        assertEquals("skipti-just-me-2026-10-31.csv", Exporter.justMeName(instant.atZone(ZoneId.of("America/Los_Angeles")), Exporter.Format.CSV))
        // iOS names it the same way: the same pattern in the phone's time zone.
        val iosExporter = java.io.File("../../ios/Skipti/App/Exporter.swift").readText()
        assertTrue("formatter.timeZone = timeZone\n        formatter.dateFormat = \"yyyy-MM-dd\"" in iosExporter)
        assertTrue("static let justMePrefix = \"${Exporter.JUST_ME_PREFIX}\"" in java.io.File("../../ios/Skipti/App/Exporter.swift").readText())
        // The personal slice: group-less expenses only.
        val me = "a".repeat(32)
        val g = "b".repeat(32)
        fun port(group: String?) = PortExpense(me, 100, "INR", group, null, listOf(ExpenseShare(me, 100)), null, null, null)
        val slice = ffiSnapshotPersonal(
            LedgerSnapshot(listOf(PortGroup(g, "G", "INR", listOf(me))), listOf(port(null), port(g)), emptyList(), emptyList(), null),
        )
        assertEquals(listOf<String?>(null), slice.expenses.map { it.group })
        assertTrue(slice.groups.isEmpty())
        val screen = main("ExportScreen.kt")
        assertTrue("justMe -> uniffi.skipti_core.ffiSnapshotPersonal(store.snapshot())" in screen)
        assertTrue("if (justMe) Exporter.justMeName(java.time.ZonedDateTime.now(), format)" in screen)
    }

    // ---- Wallet into Just me ----

    @Test
    fun a_wallet_file_goes_into_just_me_with_no_group() {
        val store = tempStore()
        assertEquals(ImportTarget.JUST_ME, ImportTarget.effective(ImportTarget.JUST_ME, ImportSource.BUDGET_BAKERS))
        assertEquals("Splitwise stays a group", ImportTarget.NEW_GROUP, ImportTarget.effective(ImportTarget.JUST_ME, ImportSource.SPLITWISE))
        assertEquals(listOf("Import into Just me", "Import as a new group"), ImportTarget.entries.map { it.title })
        val job = ImportJob.prepareJustMe(store, ImportProof.budgetBakersSheet())
        assertNull(job.group)
        assertEquals("Just me", job.ledgerName)
        assertTrue(job.plan.expenses.isNotEmpty())
        assertTrue(job.plan.expenses.all { it.group == null })
        job.commit(job.plan.expenses, store)
        assertTrue("no group is made", store.loadGroups().isEmpty())
        val me = store.ensureSelf()
        val saved = store.loadExpenses()
        assertEquals(job.plan.expenses.size, saved.size)
        assertTrue(saved.all { it.group == null && it.payer == me.id })
        assertTrue(store.allRecords().filter { it.recordType == 1.toUByte() }.all { it.groupId == null })
    }

    // ---- Delete and edit ----

    @Test
    fun just_me_deletes_in_its_own_words_and_edits() {
        assertEquals("It leaves Just me. You can undo it right after.", ExpenseDeleteCopy.message(justMe = true))
        assertEquals("It leaves your balances. You can undo it right after.", ExpenseDeleteCopy.message(justMe = false))
        assertTrue("Just me edits", ExpenseDeleteCopy.canEdit(null, null))
        assertFalse("a group this phone no longer holds does not", ExpenseDeleteCopy.canEdit("g", null))
    }

    @Test
    fun the_expenses_tab_narrows_to_just_me() {
        val me = "a".repeat(32)
        fun e(id: String, group: String?, at: Long) = ProjectedExpenseOut(
            id, ExpenseInput(me, 100, "INR", listOf(IdAmount(me, 100)), group, null),
            null, null, at.toULong(), null, at.toULong(), false, null, null, emptyList(), emptyList(),
        )
        val list = listOf(e("1", null, 3), e("2", "g", 2), e("3", null, 1))
        assertEquals(listOf<String?>(null), ExpenseSections.build(list, ExpenseSections.Filter.JustMe).map { it.groupId })
        assertEquals(listOf("1", "3"), ExpenseSections.build(list, ExpenseSections.Filter.JustMe).first().expenses.map { it.id })
        assertEquals("Just me still last", listOf("g", null), ExpenseSections.build(list, ExpenseSections.Filter.All).map { it.groupId })
        assertEquals(listOf<String?>("g"), ExpenseSections.build(list, ExpenseSections.Filter.Group("g")).map { it.groupId })
    }

    // ---- Fix wave (.drafts/d249/review.md) ----

    /** Decision 208 (I1): every expense write path checks, in core, that the payer and every share are people of the
     *  expense's ledger before anything is written: a group's members; for Just me, your own ids. Negatives per field
     *  and per path (Add, an import batch, an edit's new money); an edit keeps the people the expense already had. The
     *  twin of iOS's testAnExpenseNamesOnlyItsLedgersPeople. */
    @Test
    fun an_expense_names_only_its_ledgers_people() {
        val store = tempStore()
        store.setYourName("Sam")
        val me = store.ensureSelf()
        val goa = store.createGroup("Goa", "INR", listOf("Meera"))
        val flat = store.createGroup("Flat 4B", "INR", listOf("Kabir"))
        val meera = goa.group.members.first { it != goa.selfId }
        val kabir = flat.group.members.first { it != flat.selfId }
        fun dto(payer: String, shares: List<Pair<String, Long>>, group: String?) = ExpenseDTO(
            payer = payer, paidMinor = shares.sumOf { it.second }, currencyCode = "INR", group = group,
            shares = shares.map { ExpenseDTO.ShareDTO(it.first, it.second) }, timestampMs = 1_791_174_600_000, addedBy = payer,
        )
        fun refusedAsOutsider(write: () -> Unit): Boolean =
            runCatching(write).exceptionOrNull() is uniffi.skipti_core.CoreException.UnknownParticipant
        assertEquals("Just me is you alone", listOf(me.id), store.ledgerMembers(null))
        assertEquals(goa.group.members.toSet(), store.ledgerMembers(goa.group.id).toSet())
        assertEquals("a group not held holds nobody", emptyList<String>(), store.ledgerMembers("c".repeat(32)))
        val before = store.allRecords().size
        assertTrue("payer from another group", refusedAsOutsider { store.appendExpense(dto(kabir, listOf(goa.selfId to 100L, meera to 100L), goa.group.id)) })
        assertTrue("a share from another group", refusedAsOutsider { store.appendExpense(dto(goa.selfId, listOf(goa.selfId to 100L, kabir to 100L), goa.group.id)) })
        assertTrue("Just me with someone else", refusedAsOutsider { store.appendExpense(dto(me.id, listOf(me.id to 100L, meera to 100L), null)) })
        assertTrue("Just me paid by someone else", refusedAsOutsider { store.appendExpense(dto(meera, listOf(me.id to 100L), null)) })
        assertTrue("one outsider in an import writes none", refusedAsOutsider {
            store.appendExpenses(listOf(dto(me.id, listOf(me.id to 100L), null), dto(me.id, listOf(kabir to 100L), null)))
        })
        assertEquals("nothing was written", before, store.allRecords().size)
        // The honest ones go in.
        store.appendExpense(dto(goa.selfId, listOf(goa.selfId to 100L, meera to 100L), goa.group.id))
        store.appendExpense(dto(me.id, listOf(me.id to 100L), null))
        // An edit's new money: someone from another group is refused; the expense's own people are not.
        val id = store.projectedExpenses().expenses.first { it.expense.group == goa.group.id }.id
        fun edit(shares: List<Pair<String, Long>>) = uniffi.skipti_core.ExpenseRevisionIn(
            id, emptyList(), goa.selfId,
            ExpenseInput(goa.selfId, shares.sumOf { it.second }, "INR", shares.map { IdAmount(it.first, it.second) }, goa.group.id, null),
            null, null, null,
        )
        val records = store.allRecords().size
        assertTrue(refusedAsOutsider { store.reviseExpense(edit(listOf(goa.selfId to 100L, kabir to 200L))) })
        assertEquals("the refused edit wrote nothing", records, store.allRecords().size)
        store.reviseExpense(edit(listOf(goa.selfId to 150L, meera to 150L)))
        assertEquals(records + 1, store.allRecords().size)
    }

    /** I1: a re-created Add expense form (process death, a large-screen rotation, a theme switch) finds the group it was
     *  on by its saved id, and never keeps another group's people under it. */
    @Test
    fun a_recreated_form_keeps_its_group_and_never_another_groups_people() {
        fun g(id: String, vararg members: String) = NamedGroupWire(GroupWire(id, id, "INR", members.toList()), members.first(), emptyList())
        val a = g("a", "a1", "a2")
        val b = g("b", "b1", "b2", "b3")
        val groups = listOf(a, b)
        // Chosen B, shown A: back on B with B's people kept.
        assertEquals(AddExpenseRows.Reload(b, resetPeople = false), AddExpenseRows.reload("b", groups, a, "b2", setOf("b1", "b3")))
        // Chosen B, but the saved people are A's: B, and its people start again.
        assertEquals(AddExpenseRows.Reload(b, resetPeople = true), AddExpenseRows.reload("b", groups, a, "a1", setOf("a1", "a2")))
        assertEquals("one share outside", AddExpenseRows.Reload(b, resetPeople = true), AddExpenseRows.reload("b", groups, a, "b1", setOf("b1", "a2")))
        assertEquals("the payer outside", AddExpenseRows.Reload(b, resetPeople = true), AddExpenseRows.reload("b", groups, a, "a1", setOf("b1")))
        // The chosen group is gone (made read-only, or left): the shown one, its people again when they are not its own.
        assertEquals(AddExpenseRows.Reload(a, resetPeople = true), AddExpenseRows.reload("gone", groups, a, "b1", setOf("b1")))
        assertEquals(AddExpenseRows.Reload(a, resetPeople = false), AddExpenseRows.reload(null, groups, a, "a1", setOf("a1", "a2")))
        assertEquals(AddExpenseRows.Reload(null, resetPeople = false), AddExpenseRows.reload("b", emptyList(), null, "b1", setOf("b1")))
        // The screen saves the chosen id and resolves a reload through this function, resetting payer and people.
        val screen = main("AddExpenseScreen.kt")
        assertTrue("var chosenGroupId by rememberSaveable { mutableStateOf<String?>(null) }" in screen)
        assertTrue("val reload = AddExpenseRows.reload(chosenGroupId, all, current, payer, participants)" in screen)
        assertTrue("if (reload.resetPeople) {\n                            payer = g.selfId\n                            participants = g.group.members.toSet()" in screen)
        assertEquals("set on every choice and cleared for Just me", 4, screen.split("chosenGroupId = ").size - 1)
    }

    /** I2: the page's place (view, month, Chart or Table, last read, scroll) lives in a [SpendingKeep] held by the shell
     *  above its route switch, one per opening of the page, so an expense or Export opened from it comes back to it. */
    @Test
    fun the_page_keeps_its_place_above_the_route_switch() {
        val keep = SpendingKeep(start = SpendingMonth(2026, 10))
        assertNull(keep.opened)
        assertEquals(SpendingMonth(2026, 10), keep.month)
        assertFalse(keep.table)
        assertEquals(0, keep.scroll.value)
        keep.opened = SpendingView.ALL
        keep.month = SpendingMonth(2026, 8)
        keep.table = true
        assertEquals(SpendingView.ALL, keep.opened)
        assertEquals(SpendingMonth(2026, 8), keep.month)
        assertTrue(keep.table)
        assertEquals("a fresh one starts on this month", SpendingMonth.current(), SpendingKeep().month)
        val page = main("Spending.kt").substringAfter("fun SpendingScreen(").substringBefore("\n}\n")
        for (held in listOf("var opened by keep::opened", "var month by keep::month", "var table by keep::table", "var loaded by keep::loaded", "val scroll = keep.scroll")) {
            assertTrue(held, held in page)
        }
        assertFalse("no state of its own that a route would drop", "SpendingMonth.current()) }" in page || "rememberScrollState()" in page)
        val root = main("AppRoot.kt")
        assertTrue("val spendingKeep = remember(vaultPath, spendingOpen) { SpendingKeep() }" in root)
        assertTrue("above the route switch", root.indexOf("val spendingKeep = ") < root.indexOf("if (r == \"export-justme\")"))
        assertEquals("both openings pass it", 2, root.split("keep = spendingKeep,").size - 1)
    }

    /** M2: Home's card says it could not read the month, never "Nothing yet this month". */
    @Test
    fun the_card_says_when_the_month_could_not_be_read() {
        val (loaded, failed) = SpendingLoad.cardRead { error("vault") }
        assertNull(loaded)
        assertTrue(failed)
        assertEquals("Could not read your spending.", SpendingCopy.cardEmpty(true))
        assertEquals(SpendingCopy.EMPTY, SpendingCopy.cardEmpty(false))
        assertEquals("Your spending, Just me, Could not read your spending.", SpendingCopy.spoken("Just me", null, failed = true))
        val card = main("Spending.kt")
        // D-250 fix wave (twin 6): the line fades in and out in place (a MovingRows of its words).
        assertTrue("MovingRows(listOfNotNull(SpendingCopy.cardEmpty(failed).takeIf { first == null }), key = { \"empty\" })" in card)
        assertFalse("Text(SpendingCopy.EMPTY, " in card)
        val home = main("OverviewTab.kt")
        assertTrue("SpendingLoad.cardRead { SpendingLoad.load(store, SpendingMonth.current(), view) }" in home)
        assertTrue("keep.spendingFailed.value = read?.first?.second ?: true" in home)
        assertTrue("spending, spendingFailed, SpendingPrefs.shownView, myName, myAvatar, dark," in home)
    }

    /** M4: the Expenses tab always ends with the Just me section (empty when there is no Just me expense), so Your spending
     *  is reachable with Spending on Home off; a group's chip narrows to that group alone. iOS's twin is
     *  testTheJustMeHeaderIsAlwaysThere. */
    @Test
    fun the_just_me_header_is_always_there() {
        fun e(id: String, group: String?) = ProjectedExpenseOut(
            id, ExpenseInput("a", 1, "INR", listOf(IdAmount("a", 1)), group, null),
            null, null, 1u, null, 1u, false, null, null, emptyList(), emptyList(),
        )
        val groupOnly = listOf(e("1", "g"))
        assertEquals(listOf("g", null), ExpenseSections.build(groupOnly, ExpenseSections.Filter.All).map { it.groupId })
        assertEquals("empty, its header alone", 0, ExpenseSections.build(groupOnly, ExpenseSections.Filter.All).last().expenses.size)
        assertEquals(listOf<String?>(null), ExpenseSections.build(groupOnly, ExpenseSections.Filter.JustMe).map { it.groupId })
        assertEquals("a group's chip", listOf<String?>("g"), ExpenseSections.build(groupOnly, ExpenseSections.Filter.Group("g")).map { it.groupId })
        assertEquals("one Just me", listOf("g", null), ExpenseSections.build(listOf(e("1", "g"), e("2", null)), ExpenseSections.Filter.All).map { it.groupId })
        // D-250 fix wave (M4): the card moves as one (it leaves with its last row); the header stays.
        assertTrue("MovingRows(listOfNotNull(section.takeIf { it.expenses.isNotEmpty() }), key = { \"card\" }, leave = RowLeave.SLIDE) { carded, _ ->" in main("ExpenseListScreen.kt"))
    }

    /** M5 and M6: Add expense writes Compose state on the main thread only (read off it, set in onSuccess), and the
     *  spending switches are read in onCreate before the first frame (no flip from the defaults). */
    @Test
    fun state_is_set_on_the_main_thread_and_the_switches_load_before_the_first_frame() {
        val add = main("AddExpenseScreen.kt")
        val io = add.substringAfter("// D-249 fix wave (M5)").substringBefore("result.fold(")
        assertFalse("myName =" in io || "myAvatar =" in io)
        assertTrue("myName = me.first\n                myAvatar = me.second" in add)
        val root = main("AppRoot.kt")
        val create = root.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {").substringBefore("setContent {")
        assertTrue("SpendingPrefs.load(applicationContext)" in create)
        for ((file, text) in mainSources()) assertFalse("$file loads lazily", "LaunchedEffect(Unit) { SpendingPrefs.load" in text)
    }
}
