package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.SettledProgressOut
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.TransferOut

/** D-221 (E4, Design 1, 2, 6, 8, 11, 12): the Settled card's blocks, the group's next payment, the milestones and when
 *  the celebration plays; the twin of iOS's SettledTests, plus parity (every string of Settled.swift's `SettledCopy`
 *  is Kotlin's). */
class SettledTest {
    private fun block(code: String, bp: UInt, settled: Boolean) = SettledProgressOut(code, 0, 0, bp, settled)

    @Test
    fun the_settlement_currency_comes_first_then_by_code() {
        val list = listOf(block("USD", 0u, false), block("EUR", 0u, false), block("INR", 0u, false))
        assertEquals(listOf("INR", "EUR", "USD"), SettledSummary.blocks(list, "INR").map { it.currencyCode })
        assertEquals(listOf("EUR", "INR", "USD"), SettledSummary.blocks(list, null).map { it.currencyCode })
        assertEquals(listOf("EUR", "INR", "USD"), SettledSummary.blocks(list, "GBP").map { it.currencyCode })
        assertEquals(listOf("USD", "EUR", "INR"), SettledSummary.blocks(list, "USD").map { it.currencyCode })
    }

    @Test
    fun all_settled_needs_a_block_and_every_block_settled() {
        assertFalse("an empty list is not all settled", SettledSummary.allSettled(emptyList()))
        assertTrue(SettledSummary.allSettled(listOf(block("INR", 10000u, true), block("USD", 10000u, true))))
        assertFalse(SettledSummary.allSettled(listOf(block("INR", 10000u, true), block("USD", 2631u, false))))
    }

    @Test
    fun the_percent_is_floored() {
        assertEquals(26, SettledSummary.percent(2631u))
        assertEquals(100, SettledSummary.percent(10000u))
        assertEquals(99, SettledSummary.percent(9999u))
        assertEquals(0, SettledSummary.percent(99u))
    }

    private fun line(code: String, entries: List<Pair<String, Long>>, transfers: Int = 0) = BalanceLineOut(
        code, entries.sumOf { it.second }, entries.map { ShareOut(it.first, it.second) },
        List(transfers) { TransferOut("a", "b", 1) },
    )

    /** A pick that must exist: a missing one is an assertion, not a crash. */
    private fun picked(lines: List<BalanceLineOut>): GroupNext.Pick {
        val pick = GroupNext.pick(lines)
        assertTrue("a payment is picked", pick != null)
        return pick!!
    }

    @Test
    fun the_next_payment_is_the_largest_open_entry() {
        val lines = listOf(
            line("USD", listOf("c" to 56_000L)),
            line("INR", listOf("b" to -30_000L, "a" to 56_000L)),
        )
        // 56,000 in two currencies: the tie goes to the code first (INR before USD).
        val pick = picked(lines)
        assertEquals("INR" to "a", pick.currencyCode to pick.entry.id)
        assertFalse("Sam pays you", pick.youOwe)
        assertEquals(56_000L, pick.amountMinor)
        // The largest by size, whichever way it runs: you pay.
        val owe = picked(listOf(line("INR", listOf("a" to 20_000L, "b" to -56_000L))))
        assertEquals("b", owe.entry.id)
        assertTrue(owe.youOwe)
        assertEquals(56_000L, owe.amountMinor)
        // In one currency a tie goes to the id.
        assertEquals("x", picked(listOf(line("INR", listOf("y" to -100L, "x" to 100L)))).entry.id)
        assertNull("nothing open", GroupNext.pick(listOf(line("INR", emptyList()))))
        assertNull("no lines", GroupNext.pick(emptyList()))
    }

    /** D-247 (decision 168, D-221 b; overturns S2's pairwise count): the muted line counts the fewest-payments plan
     *  Settle up shows (core's transfers, every currency's), never the pairs. "1 payment to go" at one. The twin of iOS's
     *  testTheRemainingPaymentsCountThePlan. */
    @Test
    fun the_remaining_payments_count_the_plan() {
        val (a, c) = "a".repeat(32) to "c".repeat(32)
        // Your frame sees a owing you 900 and c owing you 450; the plan clears it in ONE transfer.
        assertEquals("two open pairs, one payment in the plan", 1, GroupNext.planCount(listOf(line("INR", listOf(a to 90_000L, c to 45_000L), transfers = 1))))
        // Each currency's plan counts.
        assertEquals(3, GroupNext.planCount(listOf(line("INR", listOf(a to 100L), transfers = 2), line("USD", listOf(a to -100L), transfers = 1))))
        // You are square, others are not: the plan still has their payment.
        assertEquals(1, GroupNext.planCount(listOf(line("INR", emptyList(), transfers = 1))))
        assertEquals(0, GroupNext.planCount(listOf(line("INR", emptyList()))))
        assertEquals(0, GroupNext.planCount(emptyList()))
        assertEquals("1 payment to go", GroupNext.remainingLine(1))
        assertEquals("2 payments to go", GroupNext.remainingLine(2))
        assertEquals("5 payments to go", GroupNext.remainingLine(5))
    }

    /** D-221 follow-up, D-247 (the plan count; twin of iOS's testTheRemovedPeopleWhoStillOweEachOtherAreCounted): removed
     *  people who still owe each other are in the plan. Worked by hand: you paid 100.00 split with Ana (Ana owes you
     *  50.00); Xavi, since removed, paid 200.00 split with Yara, also removed (Yara owes Xavi 100.00, both nets nonzero,
     *  so D-210 keeps them); Zed was removed at zero and adds nothing. Nets: you +50, Ana -50, Xavi +100, Yara -100: the
     *  fewest payments are 2 (Ana pays you, Yara pays Xavi). */
    @Test
    fun the_removed_people_who_still_owe_each_other_are_counted() {
        val (me, ana, xavi) = Triple("1".repeat(32), "2".repeat(32), "3".repeat(32))
        val (yara, zed, group) = Triple("4".repeat(32), "5".repeat(32), "9".repeat(32))
        val expenses = listOf(
            uniffi.skipti_core.ExpenseInput(me, 10_000, "INR", listOf(uniffi.skipti_core.IdAmount(me, 5_000), uniffi.skipti_core.IdAmount(ana, 5_000)), group, null),
            uniffi.skipti_core.ExpenseInput(xavi, 20_000, "INR", listOf(uniffi.skipti_core.IdAmount(xavi, 10_000), uniffi.skipti_core.IdAmount(yara, 10_000)), group, null),
        )
        val removed = listOf(xavi, yara, zed)
        fun frame(viewer: String) = uniffi.skipti_core.ffiBalanceLine(expenses, emptyList(), emptyList(), removed, viewer, group)
        assertEquals("core keeps the removed pair open", listOf(ShareOut(xavi, -10_000)), frame(yara).first().entries)
        assertEquals("your frame sees Ana only", listOf(ShareOut(ana, 5_000)), frame(me).first().entries)
        assertEquals(GroupNext.Pick("INR", ShareOut(ana, 5_000)), GroupNext.pick(frame(me)))
        val plan = GroupNext.planCount(frame(me))
        assertEquals("Ana to you, and Yara to Xavi", 2, plan)
        assertEquals("2 payments to go", GroupNext.remainingLine(plan))
        assertEquals("the plan is the group's, whoever reads it", 2, GroupNext.planCount(frame(ana)))
    }

    /** The Task 7 review's race, the twin of iOS's testTheLatchHoldsUntilThePageMovesOn: "Settling" holds from the tap
     *  until the page moves on; a second tap meanwhile, on this payment or another, records nothing (one at a time);
     *  only a failure or the page moving on lets go. */
    @Test
    fun the_latch_holds_until_the_page_moves_on() {
        val p1 = GroupNext.Pick("INR", ShareOut("a", -56_000))
        val p2 = GroupNext.Pick("INR", ShareOut("b", 20_000))
        val latch = SettleLatch<GroupNext.Pick>()
        assertFalse(latch.settling(p1))
        assertFalse(latch.busy)
        assertTrue(latch.begin(p1))
        assertTrue(latch.settling(p1))
        assertTrue("every Settle reads as disabled", latch.busy)
        assertFalse("a second tap while settling", latch.begin(p1))
        assertFalse("nor another payment while one records (Settle up's other cards)", latch.begin(p2))
        assertTrue("still settling", latch.settling(p1))
        assertFalse("the reloaded page's next pick is not settling", latch.settling(p2))
        latch.moved(listOf(p1, p2))
        assertTrue("a reload that still shows the payment keeps the latch", latch.settling(p1))
        latch.moved(listOf(p2))
        assertNull("the page moved on: it lets go", latch.inFlight)
        assertFalse(latch.busy)
        assertTrue("the next pick can be settled", latch.begin(p2))
        latch.failed()
        assertFalse("a failure lets go", latch.settling(p2))
        assertTrue("and the same pick can be tried again", latch.begin(p2))
        // M3: the pick moves on and the identical payment comes back later: it is not "Settling".
        latch.moved(listOf(p1))
        assertFalse(latch.settling(p2))
        latch.moved(listOf(p2))
        assertFalse("an identical pick returning later is a fresh one", latch.settling(p2))
        assertTrue(latch.begin(p2))
    }

    /** D-221 follow-up, the twin of iOS's testSettleUpsSecondTapRecordsNothing: Settle up's cards share one latch. Two
     *  taps on one card record one settlement; a second, different Settle while it records records nothing and its button
     *  reads as disabled; the reload (the payment gone) lets go. */
    @Test
    fun a_second_different_settle_while_one_records_records_nothing() {
        val store = seeded()
        val flat = SettleAll.sections(store).first { it.named.group.name == "Flat 4B" }
        val debts = flat.lines.flatMap { line -> line.entries.map { line.currencyCode to it } }
        assertEquals(2, debts.size)
        val keys = flat.keys
        assertEquals("one key per card", 2, keys.toSet().size)
        val latch = SettleLatch<SettleKey>()
        repeat(2) { if (latch.begin(keys[0])) SettleAll.append(store, debts[0].second, debts[0].first, flat.named) }
        assertTrue("another card's Settle reads as disabled", latch.busy)
        assertFalse("it is not the card recording", latch.settling(keys[1]))
        repeat(2) { if (latch.begin(keys[1])) SettleAll.append(store, debts[1].second, debts[1].first, flat.named) }
        val recorded = store.loadSettlements()
        assertEquals("one settlement for the taps", 1, recorded.size)
        assertTrue("only the first card's payment", debts[0].second.id in listOf(recorded.single().from, recorded.single().to))
        assertFalse(debts[1].second.id in listOf(recorded.single().from, recorded.single().to))
        val after = SettleAll.sections(store).flatMap { it.keys }
        assertFalse("the recorded card is gone", keys[0] in after)
        assertTrue(keys[1] in after)
        latch.moved(after)
        assertNull("the reload moved on", latch.inFlight)
        assertFalse(latch.busy)
        assertTrue(latch.begin(keys[1]))
    }

    /** The real path on a real vault, the twin of iOS's testASecondTapWhileSettlingRecordsNothing: you owe Ana 32.00 in
     *  Paris; two taps record one settlement; core then reads the group settled and nothing is left to pick. */
    @Test
    fun a_second_tap_while_settling_records_nothing() {
        val store = seeded()
        val paris = store.namedGroups().first { it.group.name == "Paris" }
        fun lines() = SettleAll.sections(store).first { it.named.group.id == paris.group.id }.lines
        val pick = picked(lines())
        assertEquals("EUR" to -3_200L, pick.currencyCode to pick.entry.minorUnits)
        assertEquals("1 payment to go", GroupNext.remainingLine(GroupNext.planCount(lines())))
        val latch = SettleLatch<GroupNext.Pick>()
        repeat(2) { if (latch.begin(pick)) SettleAll.append(store, pick.entry, pick.currencyCode, paris) }
        val recorded = store.loadSettlements().filter { it.group == paris.group.id }
        assertEquals("one settlement for two taps", 1, recorded.size)
        assertEquals(paris.selfId, recorded.single().from)
        assertEquals(pick.entry.id, recorded.single().to)
        assertEquals(3_200L, recorded.single().amountMinor)
        assertEquals(listOf(SettledProgressOut("EUR", 3_200, 0, 10_000u, true)), GroupProgress.progress(store, paris.group.id))
        assertNull("nothing left to pick", GroupNext.pick(lines()))
    }

    /** S2's golden (.drafts/d221/fix/android/flat4b.py, worked without core): Flat 4B is electricity 2,700.00 you paid and
     *  groceries 1,350.00 Meera paid, three ways. Pairwise: Kiran owes you 900, Meera owes you 450, Kiran owes Meera 450.
     *  Nets: you +1,350, Meera 0, Kiran -1,350: the plan is ONE transfer. D-247 (decision 168): the count is the plan's,
     *  so "1 payment to go" at every step (the pairwise view had 3, then 2, then 1). After both your settles you are
     *  square while Kiran still owes Meera 450.00 (nets: Meera +450, Kiran -450): "You're settled in Flat 4B", never "all
     *  settled". The twin of iOS's testFlat4BNextCardCountsThePlan. */
    @Test
    fun flat_4b_next_card_counts_the_plan() {
        val store = seeded()
        val flat = store.namedGroups().first { it.group.name == "Flat 4B" }
        val removed = store.removedIds(flat.group.id)
        fun view(who: String) = uniffi.skipti_core.ffiBalanceLine(
            store.loadExpenses().map { it.toInput() }, store.loadSettlements().map { it.toInput() },
            store.loadRedistributions(), removed, who, flat.group.id,
        )
        fun name(id: String) = LedgerStore.label(id, flat)
        fun section() = SettleAll.sections(store).first { it.named.group.name == "Flat 4B" }
        fun state(): Triple<String?, Long?, Int> {
            val lines = view(flat.selfId)
            val pick = GroupNext.pick(lines)
            return Triple(pick?.let { name(it.entry.id) }, pick?.entry?.minorUnits, GroupNext.planCount(lines))
        }
        assertEquals("the plan: one transfer", 1, view(flat.selfId).first().transfers.size)
        assertEquals(Triple<String?, Long?, Int>("Kiran", 90_000L, 1), state())
        assertEquals("1 payment to go", GroupNext.remainingLine(state().third))
        assertEquals(SettleState.OPEN, section().state)
        SettleAll.append(store, GroupNext.pick(view(flat.selfId))!!.entry, "INR", flat)
        assertEquals("Kiran pays you 450.00", Triple<String?, Long?, Int>("Meera", 45_000L, 1), state())
        SettleAll.append(store, GroupNext.pick(view(flat.selfId))!!.entry, "INR", flat)
        assertEquals("Kiran still owes Meera", Triple<String?, Long?, Int>(null, null, 1), state())
        val kiran = flat.members.first { name(it.id) == "Kiran" }.id
        val meera = flat.members.first { name(it.id) == "Meera" }.id
        assertEquals(listOf(ShareOut(meera, -45_000)), view(kiran).first().entries)
        // D-221 a: you square, the group not: the you-only words, from core's progress (never "all settled").
        val s = section()
        assertTrue("your own lines are empty", s.settled)
        assertFalse("core: 450.00 still outstanding", SettledSummary.allSettled(s.progress))
        assertEquals(SettleState.YOU_SETTLED, s.state)
        // Kiran pays Meera: now the whole group is square.
        SettleAll.record(store, SettlementDTO(kiran, meera, 45_000, "INR", flat.group.id))
        assertEquals(SettleState.ALL_SETTLED, section().state)
        assertEquals(0, state().third)
    }

    /** milestones.py's ten cases: inputs built as the script builds them, outputs typed from milestones.out. */
    @Test
    fun the_milestones_are_the_goldens() {
        val t0 = 1_772_409_600_000L
        val day = 86_400_000L
        val year = 365 * day
        val window = 14 * day
        fun e(added: Long, date: Long) = MilestoneExpense(added, date)
        val base = List(100) { e(t0 + it * day / 4, t0 + it * day / 4) }
        val hundred = Milestones.Shown(Milestone.HUNDRED, 1_774_548_000_000L, 1_772_409_600_000L)
        val yearOn = Milestones.Shown(Milestone.YEAR, 1_803_945_600_000L, 1_772_409_600_000L)
        val at99 = base[99].addedAtMs
        val cases = listOf(
            Triple("99 expenses, a day after the 99th", base.take(99) to base[98].addedAtMs + day, null),
            Triple("100th just added", base to at99, hundred),
            Triple("13 days 23 h after the 100th", base to at99 + window - 3_600_000, hundred),
            Triple("14 days after the 100th (gone)", base to at99 + window, null),
            Triple("a minute before the 100th was added", base to at99 - 60_000, null),
            Triple("one expense, a year on", listOf(e(t0, t0)) to t0 + year, yearOn),
            Triple("one expense, a year minus a minute", listOf(e(t0, t0)) to t0 + year - 60_000, null),
            Triple("backdated first expense", listOf(e(t0 + year, t0)) to t0 + year + day, yearOn),
            Triple(
                "both in window: the later reached wins",
                List(100) { e(t0 + year - day / 2 + it, if (it == 0) t0 else t0 + year) } to t0 + year + day,
                yearOn,
            ),
            Triple("no expenses", emptyList<MilestoneExpense>() to t0, null),
        )
        for ((name, input, want) in cases) assertEquals(name, want, Milestones.current(input.first, input.second))
    }

    @Test
    fun the_celebration_plays_only_on_the_change_to_all_settled() {
        assertTrue(Celebration.plays(before = false, after = true, isCopy = false))
        assertFalse("the first load", Celebration.plays(before = null, after = true, isCopy = false))
        assertFalse("a read-only copy", Celebration.plays(before = false, after = true, isCopy = true))
        assertFalse("already settled", Celebration.plays(before = true, after = true, isCopy = false))
        assertFalse("not settled", Celebration.plays(before = false, after = false, isCopy = false))
        assertTrue(Celebration.showsBurst(reduceMotion = false))
        assertFalse(Celebration.showsBurst(reduceMotion = true))
    }

    @Test
    fun the_words_are_the_plans() {
        assertEquals("Settled", SettledCopy.LABEL)
        assertEquals("₹2,360.00 still to settle", SettledCopy.stillToSettle("₹2,360.00"))
        assertEquals("Nothing left to settle", SettledCopy.NOTHING_LEFT)
        assertEquals("100 expenses together", SettledCopy.HUNDRED)
        assertEquals("A year together", SettledCopy.YEAR)
        assertEquals("Since 2 Mar 2026. Rare, and only once.", SettledCopy.since("2 Mar 2026"))
        assertEquals("1 payment to go", SettledCopy.paymentsToGo(1))
        assertEquals("3 payments to go", SettledCopy.paymentsToGo(3))
        assertEquals("Settle", SettledCopy.SETTLE)
        assertEquals("Settling", SettledCopy.SETTLING)
    }

    /** Parity: every string literal of iOS's `enum SettledCopy` (Settled.swift), an interpolation read as `§`, is one of
     *  Kotlin's, and every one of Kotlin's is there. */
    @Test
    fun the_settled_copy_matches_ios() {
        val kotlin = setOf(
            SettledCopy.LABEL, SettledCopy.stillToSettle("§"), SettledCopy.NOTHING_LEFT, SettledCopy.HUNDRED,
            SettledCopy.YEAR, SettledCopy.since("§"), SettledCopy.paymentsToGo(1), SettledCopy.paymentsToGo(3).replace("3", "§"),
            SettledCopy.SETTLE, SettledCopy.SETTLING,
        )
        assertEquals(kotlin, SwiftCopy.literals("Settled.swift", "enum SettledCopy"))
    }

    // ---- D-221 Task 7: the group page (Design 6, 7, 8, 11; Q16 a, Q17 a), the twins of iOS's SettledTests ----

    private fun main(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** A top-level declaration's text, from [decl] to its closing brace at column 0. */
    private fun body(file: String, decl: String): String {
        val text = main(file)
        val at = text.indexOf(decl)
        assertTrue("$file declares $decl", at >= 0)
        val end = text.indexOf("\n}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

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

    private fun seeded(): LedgerStore {
        val keystore = MemoryKeystore()
        val path = Files.createTempDirectory("settled").resolve("ledger-vault.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        GroupsProofSeed.seed(store)
        return store
    }

    /** Q16 a (L46): the header is bare: no card, no tone tint; the tile is the art table's 48 and the name the display
     *  name's size at 700. */
    @Test
    fun the_group_header_is_bare() {
        val header = body("OverviewTab.kt", "private fun GroupHeader(")
        assertFalse("no card", ".glassCard(" in header)
        assertFalse("no tone tint", "tone.fill" in header)
        assertFalse("nowhere on the page", "tint = tone.fill" in main("OverviewTab.kt"))
        assertTrue("the tile", "val side = ThemeArt.m(\"groupTile\").toInt()" in header && "GroupTile(n.group, dark, side)" in header)
        assertEquals("the name, on the tile row and over the photo", 2, header.split("Type.display(dark, 700).copy(fontSize = TypeScale.displayName.sp").size - 1 +
            header.split("Type.display(false, 700).copy(fontSize = TypeScale.displayName.sp").size - 1)
        assertFalse("no literal 26", "fontSize = 26.sp" in header)
        assertEquals(48f, ThemeArt.m("groupTile"), 0f)
    }

    /** Design 7: the page reads top to bottom: the header, the Settled card, the stats row, the milestone, the next
     *  card, then the request cards and everything below as before. */
    @Test
    fun the_group_page_order() {
        val page = body("OverviewTab.kt", "fun GroupPage(")
        val anchors = listOf("GroupHeader(n,", "SettledCard(", "StatsRow(", "MilestoneCard(", "GroupNextCard(", "cards.forEach { card ->")
        val at = anchors.map { a -> page.indexOf(a).also { assertTrue("the page draws $a", it >= 0) } }
        assertEquals("in order: $anchors at $at", at.sorted(), at)
        for (a in anchors) assertEquals("$a once", 1, page.split(a).size - 1)
        // The Settled card shows only when core returned a block; the milestone only when one shows.
        assertTrue("the Settled card's condition", "if (progress.isNotEmpty()) SettledCard(" in page)
        assertTrue("the milestone's condition", "milestone?.let { MilestoneCard(it, dark) }" in page)
    }

    /** Design 6 (L42): the meter is the theme's: C2's meter shape, the art table's heights (Vardr Labs thinner), Norse's
     *  segmented track, the fill the meter gradient across the filled width; decorative (the percent carries it). */
    @Test
    fun the_meter_is_the_themes_meter() {
        val meter = body("Settled.kt", "fun SettledMeter(")
        assertTrue("C2's meter shape", "ThemeArt.meterShape(Appearance.theme)" in meter)
        for (key in listOf("meterSegment", "meterGap")) assertTrue("reads $key", "ThemeArt.m(\"$key\")" in meter)
        assertTrue("Norse's gap colour", "ThemeArt.color(\"norseMeterGap\")" in meter)
        assertTrue("the track", "Tok.ringTrack(dark)" in meter)
        assertTrue("the gradient", "Brush.horizontalGradient(listOf(Tok.meterStart(dark), Tok.meterEnd(dark)))" in meter)
        assertTrue("hidden from TalkBack", ".clearAndSetSemantics {}" in meter)
        assertFalse("no shape of its own", "RoundedCornerShape" in meter || "CircleShape" in meter)
        val height = body("Settled.kt", "object SettledMeters")
        for (key in listOf("meterBig", "meterBigVardr", "meterSmall", "meterSmallVardr")) assertTrue("reads $key", "ThemeArt.m(\"$key\")" in height)
        assertEquals(10f, SettledMeters.height(AppTheme.AURORA, big = true), 0f)
        assertEquals(10f, SettledMeters.height(AppTheme.NORSE, big = true), 0f)
        assertEquals(7f, SettledMeters.height(AppTheme.VARDR, big = true), 0f)
        assertEquals(6f, SettledMeters.height(AppTheme.AURORA, big = false), 0f)
        assertEquals(6f, SettledMeters.height(AppTheme.NORSE, big = false), 0f)
        assertEquals(4f, SettledMeters.height(AppTheme.VARDR, big = false), 0f)
        assertEquals(0f, SettledMeters.fraction(0u), 0f)
        assertEquals(0.2631f, SettledMeters.fraction(2631u), 1e-6f)
        assertEquals(1f, SettledMeters.fraction(10000u), 0f)
        assertEquals("never past full", 1f, SettledMeters.fraction(12000u), 0f)
    }

    /** Design 6 (L43): the Settled card prints core's money (never a formatter of its own) and core's percent. */
    @Test
    fun the_settled_card_prints_core_money() {
        val card = body("Settled.kt", "fun SettledCard(")
        assertTrue("core's money", "BalanceUi.moneyFormat(b.outstandingMinor, b.currencyCode, AppLocale.current)" in card)
        assertTrue("the words", "SettledCopy.stillToSettle(" in card && "SettledCopy.NOTHING_LEFT" in card)
        assertTrue("core's percent", "\"\${SettledSummary.percent(b.basisPoints)}%\"" in card)
        assertTrue("the percent's size", "TypeScale.settledPercent" in card)
        assertTrue("the label", "SectionLabel(SettledCopy.LABEL" in card)
        assertTrue("the big meter", "SettledMeter(SettledMeters.fraction(b.basisPoints), big = true, dark)" in card)
        val file = main("Settled.kt")
        for (banned in listOf("NumberFormat", "String.format", "DecimalFormat", "\"%.")) assertFalse("no $banned", banned in file)
    }

    /** Q17 a (Design 8), D-247 (decision 182): the next card holds through Settle up's own hold (one write path, Undo for
     *  both), never a settlement of its own; Settle up holds the same way; a copy never shows the card. */
    @Test
    fun the_next_card_records_like_settle_up() {
        val card = body("Settled.kt", "fun GroupNextCard(")
        assertTrue("holds like Settle up", "SettleHold.shared.hold(SettleAll.settlement(pick.entry, pick.currencyCode, named), other, LedgerStore(vaultPath))" in card)
        assertFalse("the next card writes nothing itself", "SettleAll.record(" in card || "SettleAll.append(" in card)
        val screen = body("SettleAll.kt", "fun SettleAllScreen(")
        assertTrue("Settle up holds the same way", "hold.hold(SettleAll.settlement(entry, currency, section.named, amount), other, LedgerStore(vaultPath))" in screen)
        assertFalse("Settle up writes nothing itself: the hold does", "SettleAll.record(" in screen || "SettleAll.append(" in screen)
        assertEquals("one append, in SettleAll", 1, main("SettleAll.kt").split("appendSettlement(").size - 1)
        for (f in listOf("Settled.kt", "OverviewTab.kt")) assertFalse("$f appends nothing itself", "appendSettlement(" in main(f) || "settlementFor(" in main(f))
        assertTrue("never in a copy", "if (!isCopy && next != null) GroupNextCard(" in body("OverviewTab.kt", "fun GroupPage("))
        assertTrue("the compact button", "compact = true" in card)
        assertTrue("Settle, Settling", "if (settling) SettledCopy.SETTLING else SettledCopy.SETTLE" in card)
        assertTrue("the sentence", "SettleAllCopy.sentence(pick.youOwe, other)" in card)
        assertTrue("the muted line", "GroupNext.remainingLine(remaining)" in card)
        assertTrue("the section 11.6 line", "SettleAllCopy.EXPLAINER" in card)
        assertTrue("core's money", "BalanceUi.moneyFormat(pick.amountMinor, pick.currencyCode, AppLocale.current)" in card)
        // S1: the latch, held from the tap until the page moves on; only a failure lets go.
        assertTrue("a latch per pick", "val latch = remember(pick) { SettleLatch<GroupNext.Pick>() }" in card)
        assertTrue("a second tap records nothing", "if (!latch.begin(pick)) return@PrimaryAction" in card)
        assertEquals("only a failure lets go", 1, card.split("latch.failed(").size - 1)
        assertTrue(card.indexOf("latch.failed()") > card.indexOf("} else {"))
        assertFalse("no flag of its own", "settling = true" in card || "settling = false" in card)
        val settle = body("SettleAll.kt", "fun SettleAllScreen(")
        assertTrue("Settle up holds its card the same way", "if (!latch.begin(key)) return" in settle)
        assertEquals("and lets go on a failure", 1, settle.split("latch.failed(").size - 1)
        assertTrue("or when a reload no longer shows it", "latch.moved(found.flatMap { s -> s.keys })" in settle)
        assertEquals("one at a time: another card's Settle and Another amount read as disabled", 2, settle.split("enabled = !latch.busy").size - 1)
        assertFalse("never cleared before the reload", "settlingKey" in settle)
        // D-247 (decision 168): the count is the plan's length, from the page's own lines (core's plan).
        val page = body("OverviewTab.kt", "fun GroupPage(")
        assertTrue("the plan count", "GroupNext.planCount(l)" in page)
        assertFalse("the pairwise count is gone", "GroupNext.remaining(" in page)
    }

    /** Design 11 (L44): the seal is the theme's tile: Aurora a circle, Norse the diamond with its edge, Vardr Labs a
     *  square, through the tile's own shape, size and edge. */
    @Test
    fun the_seal_is_the_themes_tile() {
        val card = body("Settled.kt", "fun MilestoneCard(")
        assertTrue("the theme's seal", "SealStyle.clip(Appearance.theme)" in card)
        for (part in listOf("tileShape(kind, side)", "tileDrawnSide(kind, side)", ".tileEdge(kind, side)", "ThemeArt.m(\"seal\")", "Tok.accentSoft(dark)", "TypeScale.sealNumber"))
            assertTrue("the seal draws $part", part in card)
        assertFalse("no shape of its own", "CircleShape" in card || "RoundedCornerShape" in card)
        assertEquals("100", MilestoneWords.number(Milestone.HUNDRED))
        assertEquals("1", MilestoneWords.number(Milestone.YEAR))
        assertEquals(SettledCopy.HUNDRED, MilestoneWords.title(Milestone.HUNDRED))
        assertEquals(SettledCopy.YEAR, MilestoneWords.title(Milestone.YEAR))
    }

    /** Design 1 on the page's own read, against the real core and vault (GroupsProofSeed, worked by hand): Paris is
     *  €64 Ana paid, two ways, so €32 outstanding and nothing settled; once Settle up's record pays Ana, €32 is settled
     *  and nothing is outstanding. Goa trip: you +7,400, Rahul +200, Meera -3,000, Sam -4,600: ₹7,600 outstanding. */
    @Test
    fun the_page_reads_settled_progress_from_core() {
        val store = seeded()
        val groups = store.namedGroups()
        val paris = groups.first { it.group.name == "Paris" }
        val goa = groups.first { it.group.name == "Goa trip" }
        assertEquals(listOf(SettledProgressOut("EUR", 0, 3_200, 0u, false)), GroupProgress.progress(store, paris.group.id))
        assertEquals(listOf(SettledProgressOut("INR", 0, 760_000, 0u, false)), GroupProgress.progress(store, goa.group.id))
        val ana = SettleAll.sections(store).first { it.named.group.id == paris.group.id }.lines.single().entries.single()
        SettleAll.append(store, ana, "EUR", paris)
        val after = GroupProgress.progress(store, paris.group.id)
        assertEquals(listOf(SettledProgressOut("EUR", 3_200, 0, 10_000u, true)), after)
        assertTrue(SettledSummary.allSettled(after))
        assertFalse("Goa trip is untouched", SettledSummary.allSettled(GroupProgress.progress(store, goa.group.id)))
    }

    /** Design 11 on the page's own read: the group's live expenses, `timestampMs ?: addedAtMs` as the date; three
     *  expenses never reach a hundred, and a year after the first expense's date the year shows. */
    @Test
    fun the_page_reads_the_milestone_from_the_groups_expenses() {
        val store = seeded()
        val goa = store.namedGroups().first { it.group.name == "Goa trip" }
        val first = store.loadExpenses().filter { it.group == goa.group.id }.minOf { it.timestampMs!! }
        assertNull("nothing yet", GroupProgress.milestone(store, goa.group.id, System.currentTimeMillis()))
        assertEquals(
            Milestones.Shown(Milestone.YEAR, first + Milestones.YEAR_MS, first),
            GroupProgress.milestone(store, goa.group.id, first + Milestones.YEAR_MS),
        )
        assertNull("gone after the window", GroupProgress.milestone(store, goa.group.id, first + Milestones.YEAR_MS + Milestones.WINDOW_MS))
    }

    // ---- D-221 Task 9: the settled celebration (Design 12, L45), the twins of iOS's SettledTests ----

    /** GUARANTEES #2: the celebration never takes a touch. The overlay holds no pointer input, no click, no dialog, popup
     *  or sheet; it is drawn over the page as a sibling of the content, so the + and every control work through it. */
    @Test
    fun the_celebration_never_takes_a_touch() {
        val overlay = body("Settled.kt", "fun CelebrationOverlay(")
        for (taker in listOf("pointerInput", "clickable", "Dialog(", "Popup(", "selectable", "toggleable", "draggable", "scrollable", "ModalBottomSheet", "BackHandler")) {
            assertFalse("the overlay takes no touch: $taker", taker in overlay)
        }
        val page = body("OverviewTab.kt", "fun GroupPage(")
        val drawn = page.indexOf("CelebrationOverlay(")
        assertTrue("the page draws the overlay", drawn >= 0)
        assertEquals("once", 1, page.split("CelebrationOverlay(").size - 1)
        for (taker in listOf("Dialog(", "Popup(", "ModalBottomSheet", "AlertDialog")) assertFalse("the page shows it in no $taker", taker in page)
        // A sibling over the content, under the bar: after the content's scroll column, before PushedBar.
        assertTrue("after the content", page.indexOf(".verticalScroll(scroll)") < drawn)
        assertTrue("before the bar", drawn < page.indexOf("PushedBar("))
        // The page's content dims (the art behind it does not): the alpha of the content column, from the timeline.
        assertTrue("the content dims", ".graphicsLayer { alpha = play?.let { CelebrationTimeline.dim(it.elapsed, it.still) } ?: 1f }" in page)
    }

    /** The burst is the motion engine's: drawn through `artCanvas` and `ArtDraw.burst`, only when
     *  `Celebration.showsBurst` (no particles under Reduce Motion or battery saver); the play ends at
     *  `CelebrationTiming.TOTAL`. */
    @Test
    fun the_burst_is_the_engines() {
        val overlay = body("Settled.kt", "fun CelebrationOverlay(")
        val gate = overlay.indexOf("if (Celebration.showsBurst(motion.reduce))")
        assertTrue("the burst is gated", gate >= 0)
        val canvas = overlay.indexOf(".artCanvas(")
        assertTrue("through the engine's canvas", canvas > gate)
        assertTrue("drawn by ArtDraw.burst", overlay.indexOf("burst(t - play.startedAt, theme, ring, runes)") > canvas)
        assertEquals("one canvas", 1, overlay.split(".artCanvas(").size - 1)
        assertTrue("in the theme's ring", "val ring = Tok.ring(dark)" in overlay)
        val play = main("Settled.kt").substringAfter("class CelebrationPlay(").substringBefore("\n}\n")
        assertTrue("the play ends at TOTAL", "CelebrationTiming.TOTAL" in play)
        assertTrue("the still play does not tick", "delay(" in play)
        assertTrue("the moving play ticks on the shared grid", "FrameGate()" in play && "withFrameNanos" in play)
    }

    /** TalkBack hears the two lines: the words are a polite live region. Both lines are `text` (textDim fails 4.5 over
     *  the dimmed page, art.py 23). */
    @Test
    fun the_celebration_is_announced() {
        val overlay = body("Settled.kt", "fun CelebrationOverlay(")
        assertTrue("a polite live region", "liveRegion = LiveRegionMode.Polite" in overlay)
        assertTrue("the first line", "SettleAllCopy.settled(groupName)" in overlay)
        assertTrue("the second line", "SettleAllCopy.SETTLED_DETAIL" in overlay)
        assertEquals("both lines in text", 2, overlay.split("color = Tok.text(dark)").size - 1)
        assertFalse("never textDim", "textDim" in overlay || "bodyDim" in overlay)
        assertTrue("the name's role and size", "Type.display(dark, 700).copy(fontSize = TypeScale.displayName.sp" in overlay)
        assertTrue("the detail size", "fontSize = TypeScale.detail.sp" in overlay)
        assertTrue("centred at MESSAGE_TOP", "CelebrationTiming.MESSAGE_TOP" in overlay)
    }

    /** D-221 fix wave (S3, SPEC 11.7): "Do it" and "Settle" draw 40 but touch 48: the compact primary stays Material's
     *  `Button` / `OutlinedButton`, whose minimum interactive size is 48 dp, and nothing in the app turns that off. */
    @Test
    fun the_compact_button_keeps_a_48_dp_touch() {
        val action = main("GroupScreens.kt").substringAfter("internal fun PrimaryAction(").substringBefore("\n}\n")
        assertTrue("Material's Button", "    Button(\n" in action)
        assertTrue("Material's OutlinedButton", "OutlinedButton(\n" in action)
        assertFalse("no clickable of its own", ".clickable" in action)
        val sources = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!
        assertTrue(sources.size > 50)
        for (f in sources) assertFalse("${f.name} turns the 48 dp minimum off", "LocalMinimumInteractiveComponentSize" in f.readText())
        for (card in listOf(body("Settled.kt", "fun GroupNextCard("), main("AccountHubScreen.kt").substringAfter("private fun NextStepCard(").substringBefore("\n}\n"))) {
            assertTrue("the compact primary", "PrimaryAction(" in card && "compact = true" in card)
        }
    }

    /** S4 (SPEC 11.7): the Settled card's blocks and the next card's words are spoken with core's spoken money. */
    @Test
    fun the_money_views_are_spoken() {
        assertEquals("Settled, 75%, ₹450 still to settle", SettledSpeech.block("Settled", 75, "₹450"))
        assertEquals("USD, 100%, Nothing left to settle", SettledSpeech.block("USD", 100, null))
        assertEquals(
            "Kiran pays you ₹900. 3 payments to go. Marks this as paid here. It does not move money.",
            SettledSpeech.next("Kiran pays you", "₹900", "3 payments to go"),
        )
        val card = body("Settled.kt", "fun SettledCard(")
        assertTrue("core's spoken money", "BalanceUi.moneySpoken(b.outstandingMinor, b.currencyCode, AppLocale.current)" in card)
        assertTrue("one element per block", "Column(Modifier.clearAndSetSemantics { contentDescription = spoken }" in card)
        val next = body("Settled.kt", "fun GroupNextCard(")
        assertTrue("core's spoken money", "BalanceUi.moneySpoken(pick.amountMinor, pick.currencyCode, AppLocale.current)" in next)
        assertTrue("the words spoken", "contentDescription = SettledSpeech.next(SettleAllCopy.sentence(pick.youOwe, other), spoken, GroupNext.remainingLine(remaining))" in next)
        assertEquals("the button stays its own element", 1, next.split("clearAndSetSemantics").size - 1)
    }

    /** S5: Norse's diamonds are laid out by their drawn side (48 and 44 x sqrt 2), the header tile and the seal alike,
     *  so a tip never passes the page's margin or touches its text; the other shapes are unchanged. */
    @Test
    fun the_diamonds_are_laid_out_by_their_drawn_side() {
        val header = body("OverviewTab.kt", "private fun GroupHeader(")
        assertTrue("the header tile's footprint", "val drawn = tileDrawnSide(TileShape.of(Appearance.theme, person = false), side)" in header)
        assertTrue(".size(drawn.dp)" in header)
        val seal = body("Settled.kt", "fun MilestoneCard(")
        assertTrue("the seal's footprint", "Box(Modifier.size(tileDrawnSide(kind, side).dp).clearAndSetSemantics {}" in seal)
        assertEquals(67.88f, tileDrawnSide(TileShape.DIAMOND, 48), 0.01f)
        assertEquals(62.23f, tileDrawnSide(TileShape.DIAMOND, 44), 0.01f)
        assertEquals(48f, tileDrawnSide(TileShape.SQUARE, 48), 0f)
    }

    /** S6: the burst never lays text out per frame: Norse's 24 runes are measured once per theme and density, and
     *  Aurora's glow is one brush per frame. */
    @Test
    fun the_burst_measures_its_runes_once() {
        val overlay = body("Settled.kt", "fun CelebrationOverlay(")
        assertTrue("measured once", "remember(theme, density, measurer) { if (theme == AppTheme.NORSE) ArtDraw.burstRunes(measurer, density) else emptyList() }" in overlay)
        assertTrue("a cache that holds them", "rememberTextMeasurer(cacheSize = 32)" in overlay)
        val motion = main("ThemeMotion.kt")
        val burst = motion.substringAfter("fun DrawScope.burst(").substringBefore("/** Vardr Labs' particle")
        assertFalse("no measuring in the frame", "measurer.measure(" in burst.substringBefore("fun burstRunes(") || "measurer.measure(" in burst.substringAfter("private fun DrawScope.burstRune("))
        assertEquals("one glow brush per frame", 1, burst.split("Brush.radialGradient(").size - 1)
        assertTrue("built before the particles", burst.indexOf("Brush.radialGradient(") < burst.indexOf("for (i in 0 until SettledBurst.COUNT)"))
    }

    /** S9: TalkBack hears the lines from a node that is never transparent (the words fade in from 0); it starts empty
     *  and is filled once per play, a live region's change; the words on screen are hidden from it. */
    @Test
    fun talkback_hears_the_celebration_from_an_opaque_node() {
        assertEquals("Paris is all settled. Nobody owes anybody. Nice.", Celebration.announcement("Paris"))
        val overlay = body("Settled.kt", "fun CelebrationOverlay(")
        assertTrue("empty per play", "var spoken by remember(play) { mutableStateOf(\"\") }" in overlay)
        assertTrue("filled once the play begins", "spoken = Celebration.announcement(groupName)" in overlay)
        val node = overlay.substringAfter(".size(1.dp)").substringBefore("if (Celebration.showsBurst(")
        assertTrue("a polite live region", "liveRegion = LiveRegionMode.Polite" in node && "contentDescription = spoken" in node)
        assertFalse("never under the words' alpha", "graphicsLayer" in node)
        val words = overlay.substringAfter(".graphicsLayer {").substringBefore("horizontalAlignment")
        assertTrue("the faded words are hidden", ".clearAndSetSemantics {}" in words)
        assertFalse("the faded words are not the live region", "liveRegion" in words)
    }

    /** S11: the group page's cards take iOS's gaps: the next card 4 between its lines and 12 stacked (one stacking rule),
     *  the milestone 2 between its title and line, a stat tile 2 between its label and value. */
    @Test
    fun the_group_cards_take_ios_gaps() {
        val next = body("Settled.kt", "fun GroupNextCard(")
        assertTrue("4 between the lines", "verticalArrangement = Arrangement.spacedBy(4.dp)," in next)
        assertTrue("12 stacked", "Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(12.dp))" in next)
        assertTrue("one stacking rule", "TextSize.stacks(LocalDensity.current.fontScale)" in next && "1.15f" !in next)
        assertTrue("2 in the milestone", "Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp))" in body("Settled.kt", "fun MilestoneCard("))
        val stat = main("OverviewTab.kt").substringAfter("private fun Stat(").substringBefore("\n}\n")
        assertTrue("2 in a stat tile", stat.indexOf("Spacer(Modifier.height(2.dp))") in stat.indexOf("Text(label") until stat.indexOf("Text(\n            value"))
    }

    /** S11: Norse's meter draws whole segments only, as iOS's (no clipped last segment). */
    @Test
    fun the_norse_meter_draws_whole_segments() {
        val meter = body("Settled.kt", "fun SettledMeter(")
        assertTrue("whole segments", "while (x + segment <= size.width + 0.01f)" in meter)
        assertTrue("each at its full width", "size = Size(segment, size.height)" in meter)
        assertFalse("never clipped", "minOf(segment, size.width - x)" in meter)
    }

    /** The play waits until the page is seen: the load only marks it pending (the shell keeps the last settled state and
     *  the mark while the group stays open, over pushed pages); it starts when the page is composed, which is when it
     *  is the visible route, and the screen is resumed (ON_RESUME). */
    @Test
    fun the_play_waits_until_visible() {
        val page = body("OverviewTab.kt", "fun GroupPage(")
        val load = page.substringAfter("LaunchedEffect(vaultPath, groupId, SyncStatus.revision, requestIds) {").substringBefore("\n    }\n")
        assertTrue("the load decides", "Celebration.plays(celebration.before, after, isCopy = it.people.readOnly != null)" in load)
        assertTrue("and marks it pending", "celebration.pending = true" in load)
        assertTrue("then remembers the state", "celebration.before = after" in load)
        assertFalse("the load never starts it", "CelebrationPlay(" in load || "play =" in load)
        val start = page.substringAfter("LaunchedEffect(celebration.pending, motion.resumed) {").substringBefore("\n    }\n")
        assertTrue("started when pending and resumed", "if (celebration.pending && motion.resumed) {" in start)
        assertTrue("there", "play = CelebrationPlay(still = !Celebration.showsBurst(motion.reduce))" in start)
        assertEquals("started in one place", 1, page.split("CelebrationPlay(").size - 1)
        assertTrue("the page takes the shell's state", "celebration: CelebrationState = remember { CelebrationState() }," in page)
        val root = main("AppRoot.kt")
        val held = root.indexOf("val celebrations = remember { HashMap<String, CelebrationState>() }")
        assertTrue("the shell holds one per group (S8), in memory", held >= 0)
        assertFalse("never saved", "rememberSaveable { HashMap<String, CelebrationState>" in root)
        assertFalse("no longer one per open group", "remember(openGroup) { CelebrationState() }" in root)
        assertTrue("above the routes, so a pushed page, another tab or another group keeps it", held < root.indexOf("    when (route) {"))
        assertTrue("each group's own, handed to its page", "celebration = celebrations.getOrPut(id) { CelebrationState() }," in root)
        // S8: a pending play is dropped when a later load finds the group unsettled; the play ends on ON_PAUSE.
        assertTrue("a stale pending is cleared", "if (!after) celebration.pending = false" in load)
        assertTrue(load.indexOf("if (!after) celebration.pending = false") < load.indexOf("celebration.before = after"))
        assertTrue("ends on pause", "if (!motion.resumed) play = null" in start)
    }

    /** S8's decisions as a sequence on one group's memory: settled off screen plays on the next visit; a pending play a
     *  later load finds unsettled is dropped; a play already seen never plays again. */
    @Test
    fun the_per_group_memory_plays_once_and_drops_a_stale_play() {
        val memory = CelebrationState()
        fun load(after: Boolean) {
            if (Celebration.plays(memory.before, after, isCopy = false)) memory.pending = true
            if (!after) memory.pending = false
            memory.before = after
        }
        load(false)
        assertFalse(memory.pending)
        load(true) // settled in Settle up while the page was off screen: the next visit's load
        assertTrue("plays when next seen", memory.pending)
        load(false) // a new expense before it was seen
        assertFalse("a stale play is dropped", memory.pending)
        load(true)
        assertTrue(memory.pending)
        memory.pending = false // started
        load(true)
        assertFalse("never twice", memory.pending)
    }
}
