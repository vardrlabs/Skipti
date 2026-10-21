package com.vardrlabs.skipti

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.CurrencyAmountOut
import uniffi.skipti_core.PersonNetOut
import uniffi.skipti_core.SettledProgressOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import java.nio.file.Files
import java.time.ZoneId

/**
 * D-223 (Home: "Your ledgers" and "Lately"): the twin of iOS's HomePartsTests. The golden tables are retyped from
 * `.drafts/d223/art.out` (sections 35 and 36), never read back from the Swift; the copy is also compared with
 * HomeParts.swift's own literals (a declared test input).
 */
class HomePartsTest {
    @After
    fun reset() {
        AppDate.bestPattern = { locale, skeleton -> android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }
    }

    private fun p(name: String, code: String, net: Long) = PersonNetOut(name, code, net, emptyList())
    private val stub: (Long, String) -> String = { minor, _ -> "$minor" }

    /** art.out 36, A to F; F (nobody in the hero's currency) is the negative. Never more than two parts. */
    @Test
    fun who_owes_goldens() {
        val cases = listOf(
            Triple("A", listOf(p("Meera", "INR", 180000), p("Sam", "INR", -56000)), listOf("Meera owes you 180000", "you owe Sam 56000")),
            Triple("B", listOf(p("Sam", "INR", -90000), p("Meera", "INR", 20000)), listOf("You owe Sam 90000", "Meera owes you 20000")),
            Triple(
                "C", listOf(p("Asha", "INR", 50000), p("Ravi", "INR", 40000), p("Sam", "INR", 1000)),
                listOf("Asha owes you 50000", "Ravi owes you 40000"),
            ),
            Triple("D", listOf(p("Lea", "EUR", 9000), p("Meera", "INR", 180000)), listOf("Meera owes you 180000")),
            Triple("E", listOf(p("Meera", "INR", -2500)), listOf("You owe Meera 2500")),
            Triple("F", listOf(p("Lea", "EUR", 9000)), emptyList()),
        )
        for ((name, people, want) in cases) {
            val got = WhoOwes.parts(people, "INR", stub)
            assertEquals("case $name", want, got)
            assertTrue("case $name: at most two", got.size <= WhoOwes.LIMIT)
        }
        assertEquals(2, WhoOwes.LIMIT)
        // No hero currency (the settled hero): no line.
        assertEquals(emptyList<String>(), WhoOwes.parts(listOf(p("Meera", "INR", 180000)), null, stub))
        // The amount is the magnitude, formatted in the person's currency.
        assertEquals(listOf("You owe Sam INR:56000"), WhoOwes.parts(listOf(p("Sam", "INR", -56000)), "INR") { m, c -> "$c:$m" })
    }

    /** art.out 35: ms since the change to words; a negative delta reads as now; a week or more is the date. */
    @Test
    fun relative_when_goldens() {
        val now = 1_760_000_000_000L
        val rows = listOf(
            -5000L to "now", 0L to "now", 59_999L to "now", 60_000L to "1 min", 119_999L to "1 min", 120_000L to "2 min",
            3_599_999L to "59 min", 3_600_000L to "1 h", 86_399_999L to "23 h", 86_400_000L to "1 day",
            172_799_999L to "1 day", 172_800_000L to "2 days", 604_799_999L to "6 days", 604_800_000L to "date",
        )
        assertEquals(14, rows.size)
        for ((delta, want) in rows) {
            val w = RelativeWhen.of(atMs = now - delta, nowMs = now)
            if (want == "date") {
                assertEquals("$delta", RelativeWhen.Date, w)
                assertNull("$delta: the date has no words", RelativeWhen.text(w))
            } else {
                assertEquals("$delta", want, RelativeWhen.text(w))
            }
        }
        assertEquals(RelativeWhen.Now, RelativeWhen.of(atMs = now + 5000, nowMs = now))
        assertEquals(RelativeWhen.Minutes(59), RelativeWhen.of(atMs = now - 3_599_999, nowMs = now))
        assertEquals(RelativeWhen.Hours(23), RelativeWhen.of(atMs = now - 86_399_999, nowMs = now))
        assertEquals(RelativeWhen.Days(6), RelativeWhen.of(atMs = now - 604_799_999, nowMs = now))
        assertEquals("now", RelativeWhen.text(RelativeWhen.Now))
        assertEquals("1 min", RelativeWhen.text(RelativeWhen.Minutes(1)))
        assertEquals("59 min", RelativeWhen.text(RelativeWhen.Minutes(59)))
        assertEquals("1 h", RelativeWhen.text(RelativeWhen.Hours(1)))
        assertEquals("23 h", RelativeWhen.text(RelativeWhen.Hours(23)))
        assertEquals("1 day", RelativeWhen.text(RelativeWhen.Days(1)))
        assertEquals("2 days", RelativeWhen.text(RelativeWhen.Days(2)))
        assertEquals("6 days", RelativeWhen.text(RelativeWhen.Days(6)))
    }

    /** A week or more prints the day and month from the "dMMM" skeleton (JDK CLDR here, ICU on the phone), on the app's
     *  one date axis (AppLocale's en-US / en-IN, WORKLOG decision 19), never the phone's own locale. The golden is the
     *  twin of iOS's: a FIXED instant, 2026-10-16T00:00:00Z = 1_792_108_800_000 ms, in UTC; derived from CLDR's
     *  availableFormats "MMMd" ("MMM d" in en, "d MMM" in en-IN), not read back out of the formatter. */
    @Test
    fun an_older_change_reads_its_day_and_month() {
        JvmDates.install()
        val ms = 1_792_108_800_000L
        assertEquals(ms, java.time.LocalDate.of(2026, 10, 16).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        val utc = ZoneId.of("UTC")
        assertEquals("Oct 16", AppDate.dayMonth(ms, uniffi.skipti_core.LocaleTag.EN_US, utc))
        assertEquals("16 Oct", AppDate.dayMonth(ms, uniffi.skipti_core.LocaleTag.EN_IN, utc))
        assertTrue("no year", "2026" !in AppDate.dayMonth(ms, uniffi.skipti_core.LocaleTag.EN_US, utc))
        // The axis is AppLocale's: a phone in another locale (German) still reads the en-US / en-IN words.
        val phone = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("Oct 16", AppDate.dayMonth(ms, AppLocale.current, utc))
        } finally {
            java.util.Locale.setDefault(phone)
        }
        // A week or more: the date; under it, the words.
        val now = ms + 604_800_000L
        assertEquals(AppDate.dayMonth(ms), RelativeWhen.words(atMs = ms, nowMs = now))
        assertEquals(
            "16 Oct",
            RelativeWhen.words(atMs = ms, nowMs = now, tag = uniffi.skipti_core.LocaleTag.EN_IN, zone = utc),
        )
        assertEquals("6 days", RelativeWhen.words(atMs = ms + 1, nowMs = now))
    }

    /** A card is one TalkBack element in core's spoken money (SPEC 11.7, D-221 S4), never the formatted string; the
     *  percent is basis points over 100, floored; no meter, no percent. */
    @Test
    fun a_ledger_card_speaks_in_cores_words() {
        val spoken = uniffi.skipti_core.ffiMoneySpoken(180000, "INR", AppLocale.current)
        assertTrue("core says something", spoken.isNotEmpty() && "1,800" !in spoken)
        assertEquals("Goa trip, Owed $spoken, 72% settled", LedgerCards.spoken("Goa trip", LedgerLine.Owed(180000, "INR"), 7299u))
        assertEquals("Goa trip, You owe $spoken", LedgerCards.spoken("Goa trip", LedgerLine.YouOwe(180000, "INR"), null))
        assertEquals("Flat 4B, Settled, 100% settled", LedgerCards.spoken("Flat 4B", LedgerLine.Settled, 10000u))
    }

    /** views-extra 3: a zero net is skipped by the who-owes line (so the next person's "You owe" keeps its capital) and
     *  reads as settled on a ledger card. No golden above has a zero. */
    @Test
    fun a_zero_net_is_nobody_owing() {
        assertEquals(
            listOf("You owe Meera 100"),
            WhoOwes.parts(listOf(p("Zed", "INR", 0), p("Meera", "INR", -100)), "INR", stub),
        )
        assertEquals(emptyList<String>(), WhoOwes.parts(listOf(p("Zed", "INR", 0)), "INR", stub))
        assertEquals(LedgerLine.Settled, LedgerCards.line(listOf(CurrencyAmountOut("INR", 0))))
        assertEquals(LedgerLine.Settled, LedgerCards.line(listOf(CurrencyAmountOut("INR", 0), CurrencyAmountOut("EUR", 500))))
    }

    @Test
    fun ledger_line() {
        assertEquals(LedgerLine.Owed(180000, "INR"), LedgerCards.line(listOf(CurrencyAmountOut("INR", 180000))))
        assertEquals(
            "the first net only, as a magnitude",
            LedgerLine.YouOwe(56000, "INR"),
            LedgerCards.line(listOf(CurrencyAmountOut("INR", -56000), CurrencyAmountOut("EUR", 100))),
        )
        assertEquals(LedgerLine.Settled, LedgerCards.line(emptyList()))
        assertEquals("INR", LedgerCards.line(listOf(CurrencyAmountOut("INR", -1))).currency)
        assertNull(LedgerLine.Settled.currency)
    }

    private fun block(code: String, bps: Int) = SettledProgressOut(code, 0, 0, bps.toUInt(), false)

    @Test
    fun ledger_meter_block() {
        val blocks = listOf(block("INR", 7200), block("EUR", 5000))
        assertEquals(5000u, LedgerCards.basisPoints(blocks, "EUR"))
        assertEquals("absent: the first block", 7200u, LedgerCards.basisPoints(blocks, "USD"))
        assertEquals("settled line: the first block", 7200u, LedgerCards.basisPoints(blocks, null))
        assertNull("no blocks, no meter", LedgerCards.basisPoints(emptyList(), "INR"))
    }

    @Test
    fun ledger_grid_rows() {
        assertEquals(emptyList<List<Int>>(), LedgerGrid.rows(count = 0, columns = 2))
        assertEquals(listOf(listOf(0)), LedgerGrid.rows(count = 1, columns = 2))
        assertEquals(listOf(listOf(0, 1), listOf(2)), LedgerGrid.rows(count = 3, columns = 2))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), LedgerGrid.rows(count = 4, columns = 2))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), LedgerGrid.rows(count = 3, columns = 1))
        assertEquals(2, LedgerGrid.columns(accessibilitySize = false))
        assertEquals(1, LedgerGrid.columns(accessibilitySize = true))
    }

    private fun entry(id: String, at: Long) = ActivityFeed.Entry(id, at)

    @Test
    fun lately_pick() {
        assertEquals(3, Lately.COUNT)
        val five = (1..5).map { entry("e$it", 100L - it) }
        assertEquals(listOf("e1", "e2", "e3"), Lately.pick(five).map { it.id })
        assertEquals(listOf("e1", "e2"), Lately.pick(five.take(2)).map { it.id })
        assertEquals(emptyList<ActivityFeed.Entry>(), Lately.pick(emptyList()))
    }

    /** The rule Recent activity uses, re-derived by hand: newest first, a tie by id, descending. */
    @Test
    fun activity_entries_newest_first() {
        val item = ActivityFeed.Item("e1", "g", ActivityFeed.Kind.ADDED, "r", 2_000, "Taxi", 100, "INR")
        val tie = ActivityFeed.Item("e0", "g", ActivityFeed.Kind.ADDED, "r", 1_000, "Tea", 100, "INR")
        val moved = ActivityFeed.Moved("moved-x", "g", 1_500, "s", "INR", 100, listOf("r"))
        val movedTie = ActivityFeed.Moved("moved-a", "g", 1_000, "s", "INR", 100, listOf("r"))
        val got = ActivityFeed.entries(listOf(tie, item), listOf(movedTie, moved))
        assertEquals(listOf("e1", "moved-x", "moved-a", "e0"), got.map { it.id })
        assertEquals(item, got[0].item)
        assertEquals(moved, got[1].moved)
    }

    /** The shared read (Home's Lately and the Activity screen): an empty vault reads as nothing, and it never marks
     *  the feed seen (only the Activity screen does). */
    @Test
    fun the_shared_read_of_an_empty_vault() {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = Files.createTempDirectory("lately").resolve("a.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        val loaded = ActivityFeed.load(store, group = null)
        assertEquals(ActivityFeed.Loaded(emptyList(), emptyList(), emptyMap(), emptyMap()), loaded)
        assertEquals(0L, store.activitySeenUpTo())
        assertEquals(ActivityFeed.Loaded(emptyList(), emptyList(), emptyMap(), emptyMap()), ActivityFeed.load(store, group = "g"))
    }

    /** views-extra 2 (CLAUDE.md 10.1: never swallow a vault error on the launch path): a vault that will not open is an
     *  error out of the shared read, never an empty feed that Home or Activity would show as "nothing yet". */
    @Test
    fun the_shared_read_never_swallows_a_vault_error() {
        val store = LedgerStore(Files.createTempDirectory("lately").resolve("a.db").toString()) {
            throw IllegalStateException("the vault will not open")
        }
        val failed = runCatching { ActivityFeed.load(store, group = null) }
        assertTrue("an error, not an empty feed", failed.isFailure)
        assertEquals("the vault will not open", failed.exceptionOrNull()?.message)
        assertTrue(runCatching { ActivityFeed.load(store, group = "g") }.isFailure)
    }

    /** Every string in the plan's Global Constraints, verbatim; "Settled" is Balance's existing short word. */
    @Test
    fun home_copy_is_verbatim() {
        assertEquals("Your ledgers", LedgerCopy.TITLE)
        assertEquals("Owed", LedgerCopy.OWED)
        assertEquals("You owe", LedgerCopy.YOU_OWE)
        assertEquals("72% settled", LedgerCopy.settledPercent(72))
        assertEquals("Could not load the ledger", LedgerCopy.LOAD_ERROR)
        assertEquals("Lately", LatelyCopy.TITLE)
        assertEquals("Meera owes you ₹1,800.00", OverviewCopy.owesYou("Meera", "₹1,800.00"))
        assertEquals("You owe Sam ₹560.00", OverviewCopy.youOwe("Sam", "₹560.00", first = true))
        assertEquals("you owe Sam ₹560.00", OverviewCopy.youOwe("Sam", "₹560.00", first = false))
        assertEquals("Settled", OverviewCopy.SETTLED_SHORT)
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/HomeParts.kt").readText()
        assertTrue("no em dash", '\u2014' !in src)  // em-dash-ok: the escape builds the dash this test asserts is absent
    }

    // ---- Parity with iOS (HomeParts.swift and OverviewScreen.swift, read as source) ----

    private fun block(src: String, header: String): String {
        val at = src.indexOf(header)
        assertTrue("'$header' found", at >= 0)
        val open = src.indexOf('{', at + header.length - 1)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open + 1, i)
            }
        }
        error("'$header' does not close")
    }

    private fun code(s: String) = s.lines().filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")

    /** Swift's literals in a block, each `\(...)` as `{}`. */
    private fun swiftStrings(body: String) = literal.findAll(code(body)).map { it.groupValues[1].replace(Regex("""\\\([^)]*\)"""), "{}") }.toList()

    /** Kotlin's literals in a block, each `$name` or `${...}` as `{}`. */
    private fun kotlinStrings(body: String) = literal.findAll(code(body)).map {
        it.groupValues[1].replace(Regex("""\$\{[^}]*\}"""), "{}").replace(Regex("""\$[A-Za-z_]\w*"""), "{}")
    }.toList()

    @Test
    fun the_home_copy_matches_ios() {
        val swift = java.io.File("../../ios/Skipti/App/HomeParts.swift").readText()
        val overview = java.io.File("../../ios/Skipti/App/OverviewScreen.swift").readText()
        val kotlin = java.io.File("src/main/java/com/vardrlabs/skipti/HomeParts.kt").readText()
        assertEquals("LedgerCopy", swiftStrings(block(swift, "enum LedgerCopy {")), kotlinStrings(block(kotlin, "object LedgerCopy {")))
        assertEquals("LatelyCopy", swiftStrings(block(swift, "enum LatelyCopy {")), kotlinStrings(block(kotlin, "object LatelyCopy {")))
        assertEquals("EmptyCopy", swiftStrings(block(swift, "enum EmptyCopy {")), kotlinStrings(block(kotlin, "object EmptyCopy {")))
        assertEquals(
            "RelativeWhen.text",
            swiftStrings(block(swift, "static func text(")),
            kotlinStrings(block(kotlin, "fun text(")),
        )
        val both = swift + "\n" + overview
        for (fn in listOf("owesYou", "youOwe")) {
            assertEquals(
                "OverviewCopy.$fn",
                swiftStrings(block(both, "static func $fn(")),
                kotlinStrings(block(kotlin, "fun OverviewCopy.$fn(")),
            )
        }
        // The pinned words, so a change on both sides at once still fails here.
        assertTrue(swiftStrings(block(swift, "enum LedgerCopy {")).containsAll(listOf("Your ledgers", "Owed", "You owe", "{}% settled")))
        assertEquals(listOf("Lately", "See all"), swiftStrings(block(swift, "enum LatelyCopy {")))
        assertEquals(
            listOf("Nothing here yet", "Add your first expense, or import from another app."),
            swiftStrings(block(swift, "enum EmptyCopy {")),
        )
    }

    // ---- D-223 Task 4: the views, as source guards (the twins of iOS's HomePartsTests; each names its file) ----

    private fun kt(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
    private fun swift(name: String) = java.io.File("../../ios/Skipti/App/$name").readText()

    /** The body of the Kotlin function whose header (ending in its '(') is [header]: the parameter list is skipped
     *  by matching its parentheses (a default `= {}` is not the body), then the body is brace-matched. */
    private fun fn(file: String, header: String): String {
        val src = kt(file)
        assertTrue("'$header' ends in its '('", header.endsWith("("))
        assertEquals("$file: '$header' once", 1, src.split(header).size - 1)
        val at = src.indexOf(header)
        var depth = 0
        var i = at + header.length - 1
        while (true) {
            when (src[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            i++
        }
        return block(src.substring(i), "{")
    }

    private fun overviewScreen() = fn("OverviewTab.kt", "fun OverviewScreen(")

    /** Design 1: greeting, hero, "Your ledgers" and its grid, Lately, then People, in this order. */
    @Test
    fun the_home_order() {
        val body = overviewScreen()
        // D-249: the no-group hero in the hero's place, and Your spending (its label and card) under the ledgers.
        val anchors = listOf(
            "HomeGreeting(", "NoGroupHero(", "{ Hero(", "SectionLabel(LedgerCopy.TITLE, dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))",
            "LedgerGrid(", "SectionLabel(SpendingCopy.TITLE, dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))", "SpendingCard(",
            "LatelyCard(", "SectionLabel(OverviewCopy.PEOPLE, dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))",
        )
        val at = anchors.map { a -> body.indexOf(a).also { assertTrue("'$a' on Home", it >= 0) } }
        assertEquals("in order: $anchors", at.sorted(), at)
        for (a in anchors) assertEquals("'$a' once", 1, body.split(a).size - 1)
    }

    /** L55: "By group" and its horizontal scroller are gone (the halo went with it). */
    @Test
    fun the_home_has_no_scroller() {
        assertFalse("no horizontal scroller on Home", "horizontalScroll(" in overviewScreen())
        assertFalse("no By group", "BY_GROUP" in kt("OverviewTab.kt"))
        assertFalse("no By group words", "\"By group\"" in kt("OverviewTab.kt"))
    }

    /** Design 2: the who-owes line under the amount, in the hero's dim at the hero line's size, joined by the separator
     *  (Norse's gold rune), at most two lines; Settle up stays. */
    @Test
    fun the_hero_names_who_owes() {
        val hero = fn("OverviewTab.kt", "private fun Hero(")
        assertTrue("WhoOwes.parts(o.people, first.currencyCode)" in hero)
        assertTrue(Regex("""Text\(\s*Separator\.annotated\(owes\),\s*style = Type\.body\(dark\)\.copy\(color = dim, fontSize = TypeScale\.heroLine\.sp\),\s*maxLines = 2,""").containsMatchIn(hero))
        assertTrue("the hero's dim", "val dim = Tok.onHero(dark).copy(alpha = 0.82f)" in hero)
        assertTrue("only when someone owes", "if (owes.isNotEmpty())" in hero)
        assertTrue("Settle up stays", "OverviewCopy.SETTLE_UP" in hero)
        // Under the amount, inside the amount's column.
        assertTrue(hero.indexOf("autoSize = amountAutoSize(TypeScale.heroAmount)") < hero.indexOf("Separator.annotated(owes)"))
    }

    /** Design 3 and 4 (L52, L54): the Look Book's card, its values and its line, and the grid. */
    @Test
    fun the_ledger_card_is_the_look_books() {
        val card = fn("HomeParts.kt", "fun LedgerCard(")
        for (s in listOf(
            "ThemeArt.m(\"ledgerTileGap\")", "ThemeArt.m(\"ledgerCardGap\")",
            "Type.name(dark, TypeScale.ledgerName.toInt())", "SettledMeter(SettledMeters.fraction(bps), big = false, dark)",
            ".glassCard(dark)", ".clip(ThemeArt.cardShape(Appearance.theme))", "onClickLabel = GroupsCopy.OPEN_HINT",
            "maxLines = 1, overflow = TextOverflow.Ellipsis", "maxLines = 2", "LedgerCards.spoken(name, line, basisPoints)",
            ".clearAndSetSemantics { contentDescription = spoken }",
        )) assertTrue("LedgerCard: $s", s in card)
        val line = fn("HomeParts.kt", "private fun ledgerLineText(")
        for (s in listOf(
            "LedgerCopy.OWED", "LedgerCopy.YOU_OWE", "OverviewCopy.SETTLED_SHORT", "Tok.owing(dark)", "Tok.owed(dark)",
            "Tok.textDim(dark)", "Type.numericColored(TypeScale.detail, ", "FontWeight.SemiBold",
        )) assertTrue("the line: $s", s in line)
        val group = fn("HomeParts.kt", "fun GroupLedgerCard(")
        for (s in listOf("LedgerCards.line(", "LedgerCards.basisPoints(", "GroupTile(named.group, dark, ThemeArt.m(\"ledgerTile\").toInt())")) {
            assertTrue("GroupLedgerCard: $s", s in group)
        }
        val grid = fn("HomeParts.kt", "fun LedgerGrid(")
        for (s in listOf("ThemeArt.m(\"ledgerGridGap\")", "LedgerGrid.rows(", "LedgerGrid.columns(", "TextSize.stacks(LocalDensity.current.fontScale)")) {
            assertTrue("LedgerGrid: $s", s in grid)
        }
        // D-223 fix wave (M4): the house's one stacking rule (iOS's accessibility sizes), never a literal of its own.
        assertFalse("no literal switch", "1.15f" in grid)
        // Money is core's alone: no shell formatting in the views.
        val src = code(kt("HomeParts.kt"))
        for (banned in listOf("String.format(", ".format(\"%", "NumberFormat", "DecimalFormat", "\"%.")) {
            assertFalse("HomeParts.kt formats money itself: $banned", banned in src)
        }
    }

    /** Design 3: the cards in a row share its height (the row's intrinsic minimum, each card filling it). */
    @Test
    fun the_grid_rows_share_a_height() {
        val grid = fn("HomeParts.kt", "fun LedgerGrid(")
        assertTrue("the row's height", "Modifier.fillMaxWidth().height(IntrinsicSize.Min)" in grid)
        assertTrue("each card fills it", "Modifier.weight(1f).fillMaxHeight()" in grid)
        assertTrue("an odd last card keeps one column", "repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }" in grid)
    }

    /** Design 5 (L53): soft initials avatars, the shared meta line, the shared read, and the card only when it has
     *  something to say. */
    @Test
    fun lately_rows_are_soft_and_shared() {
        val row = fn("HomeParts.kt", "private fun LatelyRow(")
        assertTrue("PersonAvatar(who, dark, ThemeArt.m(\"latelyAvatar\").toInt(), fill = AvatarFill.SOFT)" in row)
        assertTrue("ThemeArt.m(\"latelyAvatarGap\")" in row)
        assertTrue("the meta line", "Separator.annotated(" in row)
        assertTrue("the words", "RelativeWhen.words(" in row)
        assertTrue("the line's role", "Type.name(dark, TypeScale.feedLine.toInt())" in row)
        val card = fn("HomeParts.kt", "fun LatelyCard(")
        assertTrue("ThemeArt.m(\"latelyRowGap\")" in card)
        assertTrue("Lately.pick(loaded.entries)" in card)
        assertTrue("the label in the card", "SectionLabel(LatelyCopy.TITLE, dark, color = Tok.text(dark))" in card)
        val home = overviewScreen()
        assertEquals(1, home.split("LatelyCard(").size - 1)
        assertTrue("only with something to show", "if (lately != null && lately.entries.isNotEmpty()) LatelyCard(" in home)
        assertFalse("Home never marks the feed seen", "setActivitySeenUpTo" in kt("OverviewTab.kt"))
    }

    /** views-extra 2: Home's Lately read is inside Home's own read, so its failure is Home's error line (iOS's twin),
     *  never an empty feed. */
    @Test
    fun home_says_when_lately_cannot_be_read() {
        val home = overviewScreen()
        // D-224 follow-up (A-R3): Home's read is `HomeKeep.read` (a create that returns to Home reads through it too),
        // taken by `HomeKeep.take`.
        assertTrue("Home reads through the shared read", "HomeKeep.read { LedgerStore(vaultPath) }" in home)
        val tab = kt("OverviewTab.kt")
        val read = tab.substringAfter("fun read(open: () -> LedgerStore)").substringBefore("\n        }\n")
        assertTrue("inside Home's read", "ActivityFeed.load(store, group = null)" in read)
        assertFalse("not caught on its own", "runCatching { ActivityFeed.load" in tab || "getOrNull()" in read)
        val failure = tab.substringAfter("    fun take(").substringAfter("onFailure = {").substringBefore("loaded.value = true")
        assertTrue("the error line", "else error.value = " in failure)
        val load = fn("ActivityFeed.kt", "    fun load(")
        assertTrue("the shared read's body", "store.activity(group)" in load && "store.namedGroups()" in load)
        assertFalse("no read swallowed in the shared read", "getOrDefault" in load || "runCatching" in load || "getOrNull" in load)
        val screen = fn("ActivityFeed.kt", "fun ActivityScreen(")
        assertTrue("the Activity screen says so too", "LedgerCopy.LOAD_ERROR" in screen)
    }

    /** A1: Lately's "See all" opens the whole ledger's Activity screen; the shell routes it. */
    @Test
    fun see_all_opens_the_whole_ledgers_activity() {
        val card = fn("HomeParts.kt", "fun LatelyCard(")
        assertTrue("LatelyCopy.SEE_ALL" in card)
        assertTrue("onSeeAll" in card)
        // D-223 fix wave (decision 21): the card's own link colour, as "People in X"; never the page-ground token.
        assertTrue("the link colour", "color = Tok.accent(dark)" in card)
        assertFalse("not the ground's", "groundAccent" in card)
        val shell = kt("AppRoot.kt")
        assertTrue("onSeeAllActivity = { route = \"activity\" }" in shell.substringAfter("OverviewScreen(").substringBefore("\n                            )"))
        assertTrue(Regex("""if \(r == "activity"\) \{\s*ActivityScreen\(\s*vaultPath, dark, group = null,""").containsMatchIn(shell))
    }

    /** Home's Lately rows open the expense through the shell's existing route. */
    @Test
    fun the_lately_rows_open_the_expense() {
        assertTrue("onOpenExpense = onOpenExpense" in overviewScreen())
        val row = fn("HomeParts.kt", "private fun LatelyRow(")
        assertTrue("Modifier.clickable(onClickLabel = OverviewCopy.OPEN_EXPENSE) { onOpenExpense(item.expenseId) }" in row)
        assertTrue("a deletion or an unknown group is not opened", "item.kind != ActivityFeed.Kind.DELETED && named != null" in row)
        val call = kt("AppRoot.kt").substringAfter("OverviewScreen(").substringBefore("\n                            )")
        assertTrue("the shell passes it", "onOpenExpense = { route = \"expense/\$it\" }" in call)
    }

    /** Design 6: Recent activity and Activity say "when" with RelativeWhen, through the shared read. */
    @Test
    fun activity_uses_relative_when() {
        val feed = kt("ActivityFeed.kt")
        assertFalse("getRelativeTimeSpanString" in feed)
        assertFalse("DateUtils" in feed)
        assertEquals("two rows", 2, feed.split("RelativeWhen.words(").size - 1)
        assertTrue("ActivityFeed.load(store, group)" in fn("ActivityFeed.kt", "fun ActivityScreen("))
        assertEquals("the read is written once (views-extra 5)", 1, feed.split("store.activity(group)").size - 1)
    }

    /** Design 7 (L-D217-10), A2: Expenses' empty state and its load error are the drawn EmptyState, with SPEC 11.6's
     *  words verbatim ("Nothing here yet. Add your first expense, or import from another app."). */
    @Test
    fun expenses_empty_is_drawn() {
        val list = kt("ExpenseListScreen.kt")
        assertEquals(2, list.split("EmptyState(").size - 1)
        assertTrue("EmptyState(EmptyCopy.EXPENSES_TITLE, EmptyCopy.EXPENSES_DETAIL, dark)" in list)
        assertTrue("EmptyState(LedgerCopy.LOAD_ERROR, loadError!!, dark)" in list)
        assertFalse("no plain empty line", "EMPTY_LEDGER_COPY" in list)
        assertEquals("Nothing here yet", EmptyCopy.EXPENSES_TITLE)
        assertEquals("Add your first expense, or import from another app.", EmptyCopy.EXPENSES_DETAIL)
        assertEquals(
            "SPEC 11.6's line, whole",
            "Nothing here yet. Add your first expense, or import from another app.",
            "${EmptyCopy.EXPENSES_TITLE}. ${EmptyCopy.EXPENSES_DETAIL}",
        )
        val empty = fn("HomeParts.kt", "fun EmptyState(")
        for (s in listOf(
            "TabIcons.Inbox", "Modifier.size(ThemeArt.m(\"emptyIcon\").dp)", "tint = Tok.textDim(dark)",
            "Spacer(Modifier.height(12.dp))", "Type.name(dark, TypeScale.emptyTitle.toInt())", "Spacer(Modifier.height(4.dp))",
            "Type.bodyDim(dark)", "TextAlign.Center", ".padding(vertical = 16.dp)",
        )) assertTrue("EmptyState: $s", s in empty)
        // D-223 fix wave (F3): iOS's EmptyState pads 16 at full width; here the tab root already insets 16 a side, so
        // the state adds none (32 a side wrapped the detail with "app." alone on its line).
        assertFalse("no side padding of its own", ".padding(16.dp)" in empty)
        assertTrue("the tab root's sides", "padding(start = 16.dp, end = 16.dp," in kt("SkiptiBar.kt"))
        assertTrue("iOS's", "        .padding(16)\n        .frame(maxWidth: .infinity, maxHeight: .infinity)" in swift("HomeParts.swift"))
        // Balance's own empty title takes the same role and size.
        assertTrue("Text(title, style = Type.name(dark, TypeScale.emptyTitle.toInt()))" in fn("OverviewTab.kt", "private fun EmptyHome("))
    }

    /** Design 8 (L-D217-1): the plain Groups card's spacing is iOS's, from the one table, and its row has no 48 dp floor
     *  of its own (the tile is 48 already). */
    @Test
    fun groups_card_spacing() {
        val groups = kt("GroupsTab.kt")
        val plain = groups.substringAfter("PhotoGroupCard(named, photo, dark,").substringBefore("// D-210 (Task 8 look)")
        assertTrue("Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(ThemeArt.m(\"groupsCardSpacing\").dp))" in plain)
        assertFalse("no 8 dp", "spacedBy(8.dp)" in plain)
        assertFalse("no row floor", ".heightIn(min = 48.dp)" in plain)
    }

    /** Design 5: PersonAvatar takes an optional fill: none keeps the tone, SOFT gives accentSoft with the accent ink. */
    @Test
    fun person_avatar_soft_fill() {
        val avatar = fn("RemovalFlow.kt", "fun PersonAvatar(")
        assertTrue("fill: AvatarFill? = null" in kt("RemovalFlow.kt"))
        assertTrue(".background(fill?.ground?.invoke(dark) ?: tone.fill(dark), tileShape(kind, size))" in avatar)
        assertTrue("fill?.ink?.invoke(dark) ?: tone.letter(dark)" in avatar)
        assertEquals(Tok.accentSoft to Tok.accent, AvatarFill.SOFT.ground to AvatarFill.SOFT.ink)
    }

    /** views-extra 6: TabIcons.kt names the true number of drawings. */
    @Test
    fun tab_icons_names_its_drawings() {
        val icons = kt("TabIcons.kt")
        // D-224: `vibrate` (the Haptics row) makes eighteen; D-225: `x` and `lock` make twenty; D-216 takes the bell
        // away (the cut S is Android's notification icon now): nineteen; D-231: `sparkles` (Less motion) makes twenty;
        // D-249: `chart-pie` (Spending on Home) makes twenty-one.
        assertEquals(21, TabIcons.PATHS.size)
        assertFalse("four path strings" in icons)
        assertFalse(Regex("(?i)(sixteen|seventeen|eighteen|nineteen|twenty) drawings").containsMatchIn(icons))
        assertTrue("twenty-one drawings" in icons)
    }

    // ---- D-223 fix wave (Android) ----

    /** I2: an expense opened from an Activity screen goes back to that Activity, as iOS's push does (Back, and the pop
     *  after a delete); its edit goes back to it. The whole ledger's Activity ("See all") and a group's Recent activity
     *  both. An expense opened from Home's Lately, a group's page or the list keeps its old way back (Home, the page). */
    @Test
    fun an_activity_expense_returns_to_its_activity() {
        val e = "0123456789abcdef0123456789abcdef"
        val g = "fedcba9876543210fedcba9876543210"
        assertEquals("ledger-activity/expense/$e", ActivityExpenseRoute.of("activity", e))
        assertEquals("activity/$g/expense/$e", ActivityExpenseRoute.of("activity/$g", e))
        assertEquals("activity", HomeRoutes.parent("ledger-activity/expense/$e"))
        assertEquals("activity/$g", HomeRoutes.parent("activity/$g/expense/$e"))
        assertEquals(ActivityExpenseRoute.Open("activity", e), ActivityExpenseRoute.parse("ledger-activity/expense/$e"))
        assertEquals(ActivityExpenseRoute.Open("activity/$g", e), ActivityExpenseRoute.parse("activity/$g/expense/$e"))
        // Its edit returns to the expense it was opened from, wherever that was opened.
        assertEquals("ledger-activity/expense/$e", HomeRoutes.parent("expense-edit/$e/from/ledger-activity/expense/$e"))
        assertEquals("activity/$g/expense/$e", HomeRoutes.parent("expense-edit/$e/from/activity/$g/expense/$e"))
        // Negatives: the Activity screens themselves, and the other ways into an expense, are unchanged.
        assertNull(ActivityExpenseRoute.parse("activity"))
        assertNull(ActivityExpenseRoute.parse("activity/$g"))
        assertNull(ActivityExpenseRoute.parse("expense/$e"))
        assertNull(ActivityExpenseRoute.parse(null))
        assertNull(HomeRoutes.parent("activity"))
        assertNull(HomeRoutes.parent("activity/$g"))
        assertNull(HomeRoutes.parent("expense/$e"))
        assertEquals("expense/$e", HomeRoutes.parent("expense-edit/$e/detail"))
        // The shell opens them so, and draws them with the detail screen going back to its Activity.
        val shell = kt("AppRoot.kt")
        assertTrue(Regex("""if \(r == "activity"\) \{\s*ActivityScreen\(\s*vaultPath, dark, group = null,\s*onOpenExpense = \{ route = ActivityExpenseRoute\.of\(r, it\) \}""").containsMatchIn(shell))
        assertTrue(Regex("""ActivityScreen\(\s*vaultPath, dark, group = r\.removePrefix\("activity/"\),\s*onOpenExpense = \{ route = ActivityExpenseRoute\.of\(r, it\) \}""").containsMatchIn(shell))
        val drawn = shell.substringAfter("val opened = ActivityExpenseRoute.parse(r)").substringBefore("return")
        assertTrue("Back to its Activity", "onBack = { route = opened.activity }" in drawn)
        assertTrue("an edit comes back to it", """onEdit = { route = "expense-edit/${'$'}{opened.expense}/from/${'$'}r" }""" in drawn)
        assertTrue("a delete pops to its Activity", "route = opened.activity" in drawn.substringAfter("onDeleted"))
        // Before the group's Activity route, which would otherwise read "<g>/expense/<id>" as a group.
        assertTrue(shell.indexOf("val opened = ActivityExpenseRoute.parse(r)") < shell.indexOf("if (r != null && r.startsWith(\"activity/\"))"))
    }

    /** F4: Home's two section labels take iOS's `section()` top padding (4). */
    @Test
    fun home_section_labels_take_ioss_top_padding() {
        assertTrue("iOS's", "Text(verbatim: text).sectionLabel().foregroundStyle(Tokens.text).padding(.top, 4)" in swift("OverviewScreen.swift"))
        val home = overviewScreen()
        // D-249: + Your spending's.
        assertEquals("the three labels", 3, home.split("dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))").size - 1)
    }

    /** F5: Norse's ornament on a tab root. iOS draws it in the scroll content with a negative top padding whose padded
     *  frame never goes below zero height, so iOS's first content starts max(15 + above, 8 + 16) under the title's text
     *  (`titleBottomInset` 15, the 8 gap, the 16 row), then its stack's spacing; Android's scaffold put it at
     *  8 + 16 + (above - 8), then 14. Retyped by hand: Groups (above 8, spacing 14) max(23, 24) = 24 against 24, so 0;
     *  Balance with data (16, 14) 31 against 32, so -1 (measured +1.3 on the look); Balance empty (16, its VStack's
     *  spacing 12) -1 - 2 = -3 (measured +2.67). D-226: the row is 20 (it holds the 17 dp rune), retyped the same way:
     *  Groups max(23, 28) = 28 against 28, so 0; Balance with data 31 against 36, so -5; Balance empty -5 - 2 = -7. */
    @Test
    fun the_norse_ornament_hands_over_as_on_ios() {
        assertEquals(0, TitleOrnaments.contentShift(iosAbove = 8))
        assertEquals(-5, TitleOrnaments.contentShift(iosAbove = 16))
        assertEquals(-7, TitleOrnaments.contentShift(iosAbove = 16, iosSpacing = 12))
        assertEquals("iOS's inset", 15, TitleOrnaments.IOS_TITLE_BOTTOM_INSET)
        assertTrue("static let titleBottomInset: CGFloat = 15" in swift("ThemeArt.swift"))
        // The scaffold applies it in Norse only, and Home's empty state hands over iOS's empty VStack spacing.
        val bar = kt("SkiptiBar.kt")
        assertTrue(
            "val extraTop = BarDecisions.tabRootExtra(iosPadding) + " +
                "(if (ornament && TitleOrnaments.shows(Appearance.theme)) TitleOrnaments.contentShift(iosPadding, iosOrnamentSpacing) else 0)" in bar,
        )
        assertTrue("iOS's empty()", "VStack(alignment: .leading, spacing: 12) {\n            if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 16) }" in swift("OverviewScreen.swift"))
        assertEquals(12, HomeEmpty.SPACING)
        assertTrue("iosOrnamentSpacing = if (emptyHome) HomeEmpty.SPACING else BarDecisions.CONTENT_SPACING," in overviewScreen())
        // D-249: only "Add your name" is an empty state now (with no group yet the hero asks, in the page's stack).
        assertTrue("val emptyHome = needsName\n" in overviewScreen())
        assertTrue("Column(verticalArrangement = Arrangement.spacedBy(HomeEmpty.SPACING.dp))" in fn("OverviewTab.kt", "private fun EmptyHome("))
    }

    /** D-223 follow-up (R2): Norse Expenses with rows hands over from the ornament with iOS's list stack spacing (12,
     *  ExpenseListView's ScrollView VStack), so the chips sit 26.3 under the rule as on iOS (Android was 28.2); its
     *  empty, loading and error states keep the default (iOS centres them under a 0-spaced ornament). */
    @Test
    fun norse_expenses_hand_over_with_the_lists_spacing() {
        val ios = swift("ExpenseListView.swift")
        val list = "ScrollView {\n                    VStack(alignment: .leading, spacing: ${ExpenseListUi.SPACING}) {\n" +
            "                        if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 16) }"
        assertTrue("iOS's list stack", list in ios)
        assertEquals(12, ExpenseListUi.SPACING)
        // D-226: -7 with the 20 dp ornament row (-3 with the 16 before; HomePartsTest's F5 rows).
        assertEquals(-7, TitleOrnaments.contentShift(iosAbove = BarDecisions.TAB_ROOT_ROOMY_PADDING, iosSpacing = ExpenseListUi.SPACING))
        val screen = fn("ExpenseListScreen.kt", "fun ExpenseListScreen(")
        assertTrue("val listShown = loadError == null && !expenses.isNullOrEmpty()" in screen)
        assertTrue("iosOrnamentSpacing = if (listShown) ExpenseListUi.SPACING else BarDecisions.CONTENT_SPACING," in screen)
        assertTrue("the list's own stack", "Column(verticalArrangement = Arrangement.spacedBy(ExpenseListUi.SPACING.dp)) {" in screen)
    }

    /** D-223 follow-up (R3): the tab bar's measured height is kept above the route switch, so Home's first frame back
     *  from a pushed route keeps the bar's height clear at its end and the kept offset near the end is not clamped by a
     *  0 inset (it landed 76.19 dp, the bar's height, higher). */
    @Test
    fun the_tab_bars_height_outlives_a_route() {
        val shell = kt("AppRoot.kt")
        val decl = "var tabBarPx by rememberSaveable { mutableIntStateOf(0) }"
        assertEquals("declared once", 1, shell.split(decl).size - 1)
        assertTrue("above the route switch", shell.indexOf(decl) in 0 until shell.indexOf("    when (route) {"))
        assertTrue("beside Home's kept scroll", shell.indexOf(decl) > shell.indexOf("val homeKeep = remember(vaultPath) { HomeKeep(homeScroll) }"))
        assertFalse("no second, per-entry one", "var tabBarPx by remember {" in shell)
        assertTrue("the shell provides it", "LocalShellBarInset provides tabBarInset" in shell)
        assertTrue("measured from the bar", ".onSizeChanged { tabBarPx = it.height }" in shell)
    }

    /** F6: Home keeps its place under a pushed route (an expense, a group's page, Activity), as iOS's NavigationStack
     *  keeps it: the shell owns Home's last read and its scroll state above the route switch (a route replaces the
     *  tabs, so Home leaves the composition), and Home draws from them at once on its return, so the kept offset is
     *  never clamped by an empty first frame. A re-tap's scroll to the top is a change while on screen, never replayed. */
    @Test
    fun home_keeps_its_place() {
        val shell = kt("AppRoot.kt")
        val keep = "val homeKeep = remember(vaultPath) { HomeKeep(homeScroll) }"
        assertTrue(keep in shell)
        assertTrue("val homeScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }" in shell)
        assertTrue("above the route switch", shell.indexOf(keep) < shell.indexOf("    when (route) {"))
        assertTrue("Home is handed it", "keep = homeKeep," in shell.substringAfter("OverviewScreen(").substringBefore("\n                            )"))
        val home = overviewScreen()
        for (v in listOf("groups", "overview", "progress", "latelyRead", "needsName", "loaded", "error")) {
            assertTrue("Home's $v is the kept one", "val $v by keep.$v" in home)
        }
        assertFalse("no state of its own", "by remember { mutableStateOf" in home)
        assertTrue("scroll = rememberShellScrollState(keep.scroll)," in home)
        // The re-tap rule: only a signal that changed since this screen came on.
        assertFalse(ShellScrollTop.scrolls(signal = 0, atEntry = 0))
        assertFalse("a kept state is not sent to the top again", ShellScrollTop.scrolls(signal = 3, atEntry = 3))
        assertTrue(ShellScrollTop.scrolls(signal = 1, atEntry = 0))
        assertTrue(ShellScrollTop.scrolls(signal = 4, atEntry = 3))
        val rule = kt("TabBar.kt").substringAfter("fun rememberShellScrollState(state: ScrollState): ScrollState {").substringBefore("\n}")
        // Fix wave I1: a jump, not a scroll, when motion is reduced (ThemeMotionTest pins the gate).
        assertTrue("if (ShellScrollTop.scrolls(signal, atEntry)) { if (reduce) state.scrollTo(0) else state.animateScrollTo(0) }" in rule)
    }

    /** D-225 T7 (A5, D-223's fix re-review m4): Expenses, Groups and Settle up keep their place as Home does (F6): the
     *  shell owns each tab's saved scroll state and its last read above the route switch (a route replaces the tabs and
     *  another tab replaces this one, so the screen leaves the composition), each screen draws from its keep's slots on
     *  the first frame back (so the kept offset is never clamped by an empty first frame) and scrolls on the kept state. */
    @Test
    fun every_tab_keeps_its_place() {
        val shell = kt("AppRoot.kt")
        val routeSwitch = shell.indexOf("    when (route) {")
        for ((tab, screen) in listOf("expenses" to "ExpenseListScreen.kt", "groups" to "GroupsTab.kt", "settle" to "SettleAll.kt")) {
            val scroll = "val ${tab}Scroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }"
            val keep = "val ${tab}Keep = remember(vaultPath) { TabKeep(${tab}Scroll) }"
            assertTrue("$tab: its offset is saved", scroll in shell)
            assertTrue("$tab: its keep", keep in shell)
            assertTrue("$tab: above the route switch", shell.indexOf(keep) in 0 until routeSwitch)
            assertTrue("$tab: handed to the screen", "keep = ${tab}Keep" in shell)
            val s = kt(screen)
            assertTrue("$screen scrolls on the kept state", "scroll = rememberShellScrollState(keep.scroll)," in s)
            assertTrue("$screen reads from its keep", "by keep.slot" in s)
        }
        // What each screen draws first comes from the keep, never a fresh empty state.
        val list = kt("ExpenseListScreen.kt")
        // D-249: the chip is a filter now (a group or Just me).
        for (v in listOf("projected", "loadError", "groups", "filter")) assertTrue("Expenses' $v is kept", "var $v by keep.slot" in list)
        val groups = kt("GroupsTab.kt")
        for (v in listOf("groups", "waiting", "selectedId", "error", "loaded", "copies")) assertTrue("Groups' $v is kept", "var $v by keep.slot" in groups)
        val settle = kt("SettleAll.kt")
        for (v in listOf("sections", "error", "loaded")) assertTrue("Settle up's $v is kept", "var $v by keep.slot" in settle)
    }

    /** A [TabKeep]'s slot is the same state every time it is asked for (the screen's next entry reads what the last one
     *  wrote), made once from its initial value; its scroll state is the one the shell handed it. */
    @Test
    fun a_tab_keep_hands_back_the_same_state() {
        val scroll = androidx.compose.foundation.ScrollState(240)
        val keep = TabKeep(scroll)
        assertTrue(keep.scroll === scroll)
        val first = keep.slot("rows") { listOf("a") }
        first.value = listOf("a", "b")
        var made = 0
        val again = keep.slot("rows") { made++; listOf("fresh") }
        assertTrue("the same state", first === again)
        assertEquals(listOf("a", "b"), again.value)
        assertEquals("not made again", 0, made)
        assertEquals("another name is its own", null, keep.slot<String?>("other") { null }.value)
    }
}
