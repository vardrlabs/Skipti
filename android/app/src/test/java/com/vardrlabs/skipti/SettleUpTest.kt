package com.vardrlabs.skipti

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.SettledProgressOut
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import java.nio.file.Files

/** D-247 (U29, decisions 167 to 170, 182, 183): Settle up's other amount and its cap, the 5 s hold behind Undo, the
 *  settled words, and the creator fall-back. The twin of iOS's SettleUpTests, against the real core and vault. */
class SettleUpTest {
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

    private fun tempStore(): LedgerStore {
        val keystore = MemoryKeystore()
        val path = Files.createTempDirectory("settle-up").resolve("ledger-vault.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    /** The holds' timers run here (never the main thread's, which a JVM test has not got); cancelled after each test. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stopTimers() = scope.cancel()

    private fun src(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    private fun count(text: String, needle: String) = text.split(needle).size - 1

    /** A centre that plays and says nothing (no root view: no haptic). */
    private fun quietCenter() = DoneMomentCenter()

    /** Paris from the groups proof's shape: Ana paid 64.00 split with you, so you owe Ana 32.00. */
    private fun paris(store: LedgerStore): Pair<NamedGroupWire, String> {
        store.setYourName("Prithvi")
        val named = store.createGroup("Paris", "EUR", listOf("Ana"))
        val ana = named.members.first { it.id != named.selfId }.id
        store.appendExpense(
            ExpenseDTO(
                payer = ana, paidMinor = 6_400, currencyCode = "EUR", group = named.group.id,
                shares = listOf(ExpenseDTO.ShareDTO(named.selfId, 3_200), ExpenseDTO.ShareDTO(ana, 3_200)), note = "Museum passes",
                timestampMs = 1_772_409_600_000, category = null, addedBy = ana,
            ),
        )
        return named to ana
    }

    // ---- The copy ----

    /** The words, verbatim on both phones (iOS's SettleUpTests holds the same table), none with an em or en dash. */
    @Test
    fun the_copy_table() {
        val table = listOf(
            SettleAllCopy.ANOTHER_AMOUNT to "Another amount",
            SettleAllCopy.HOW_MUCH_PAID to "How much was paid?",
            SettleAllCopy.SETTLE to "Settle",
            SettleAllCopy.CANCEL to "Cancel",
            SettleAllCopy.moreThanDebt(youOwe = false, other = "Ana") to "That is more than Ana owes.",
            SettleAllCopy.moreThanDebt(youOwe = true, other = "Ana") to "That is more than you owe Ana.",
            SettleAllCopy.EXPLAINER to "Marks this as paid here. It does not move money.",
            SettleAllCopy.youSettled("Flat 4B") to "You're settled in Flat 4B",
            SettleAllCopy.YOU_SETTLED_DETAIL to "You don't owe anything here.",
            SettleAllCopy.settled("Flat 4B") to "Flat 4B is all settled",
            SettleAllCopy.SETTLED_DETAIL to "Nobody owes anybody. Nice.",
            DoneCopy.settledWith("Ana") to "Settled with Ana. No money moved.",
            DoneCopy.UNDO to "Undo",
            DoneCopy.NOT_RECORDED to "Not recorded.",
            SettledCopy.paymentsToGo(1) to "1 payment to go",
            SettledCopy.paymentsToGo(4) to "4 payments to go",
        )
        for ((got, want) in table) {
            assertEquals(want, got)
            assertFalse(got, "—" in got || "–" in got) // em-dash-ok: asserts the dashes are absent
        }
    }

    /** Parity: iOS's table (SettleUpTests.swift's testTheCopyTable) holds the same pairs, so the two phones say the same. */
    @Test
    fun the_copy_table_is_ios() {
        val ios = java.io.File("../../ios/SkiptiTests/SettleUpTests.swift").readText()
        val table = ios.substringAfter("func testTheCopyTable()").substringBefore("for (got, want) in table")
        val wants = Regex("""(?m), "((?:[^"\\]|\\.)*)"\),\s*$""").findAll(table).map { it.groupValues[1] }.toList()
        assertEquals(16, wants.size)
        val kotlin = listOf(
            SettleAllCopy.ANOTHER_AMOUNT, SettleAllCopy.HOW_MUCH_PAID, SettleAllCopy.SETTLE, SettleAllCopy.CANCEL,
            SettleAllCopy.moreThanDebt(false, "Ana"), SettleAllCopy.moreThanDebt(true, "Ana"), SettleAllCopy.EXPLAINER,
            SettleAllCopy.youSettled("Flat 4B"), SettleAllCopy.YOU_SETTLED_DETAIL, SettleAllCopy.settled("Flat 4B"),
            SettleAllCopy.SETTLED_DETAIL, DoneCopy.settledWith("Ana"), DoneCopy.UNDO, DoneCopy.NOT_RECORDED,
            SettledCopy.paymentsToGo(1), SettledCopy.paymentsToGo(4),
        )
        assertEquals(wants, kotlin)
    }

    // ---- Another amount (decisions 167, 183) ----

    /** The field starts at the whole debt in core's entry form; it settles anything above 0 up to the debt; more is
     *  refused in words (both directions); empty, zero and what is not an amount keep Settle disabled with nothing said. */
    @Test
    fun the_other_amount_is_capped_at_the_debt() {
        assertEquals("450.00", OtherAmount.prefill(45_000, "INR", java.util.Locale.US))
        assertEquals("1005", OtherAmount.prefill(1_005, "JPY", java.util.Locale.US))
        val ok: (Long) -> Unit = {}
        fun verdict(text: String, youOwe: Boolean = false) =
            OtherAmount.verdict(text, 45_000, "INR", youOwe, "Ana", locale = java.util.Locale.US, check = ok)
        assertEquals("the whole debt", OtherAmount.Verdict.Settles(45_000), verdict("450.00"))
        assertEquals(OtherAmount.Verdict.Settles(45_000), verdict("450"))
        assertEquals("a partial", OtherAmount.Verdict.Settles(20_000), verdict("200"))
        assertEquals("the least", OtherAmount.Verdict.Settles(1), verdict(" 0.01 "))
        // The cap (decision 183): a partial never flips who owes whom.
        assertEquals(OtherAmount.Verdict.TooMuch("That is more than Ana owes."), verdict("450.01"))
        assertEquals(OtherAmount.Verdict.TooMuch("That is more than you owe Ana."), verdict("451", youOwe = true))
        assertEquals(OtherAmount.Verdict.TooMuch("That is more than Ana owes."), verdict("99999999"))
        // Settle disabled, nothing said.
        for (text in listOf("", "  ", "0", "0.00", "-5")) {
            assertEquals("'$text'", OtherAmount.Verdict.Incomplete, verdict(text))
        }
        // D-248 (U30.2): what is not an amount is said (D248FixesTest has the locales and the comma).
        for (text in listOf("abc", "1.2.3", "₹10")) {
            assertEquals("'$text'", OtherAmount.Verdict.NotAnAmount(AmountEntry.likeWords("INR", java.util.Locale.US)), verdict(text))
        }
        // Core's check refusing reads as incomplete (never settles).
        assertEquals(
            OtherAmount.Verdict.Incomplete,
            OtherAmount.verdict("10", 45_000, "INR", false, "Ana") { throw uniffi.skipti_core.CoreException.InvalidInput("no") },
        )
        assertFalse(OtherAmount.settles(OtherAmount.Verdict.Incomplete))
        assertFalse(OtherAmount.settles(OtherAmount.Verdict.TooMuch("x")))
        assertTrue(OtherAmount.settles(OtherAmount.Verdict.Settles(1)))
    }

    /** A partial settle, through the hold, on a real vault: while held the card shows the remainder (core's lines with the
     *  held settlement counted); once written, the store holds exactly the partial and the card the remainder. */
    @Test
    fun a_partial_leaves_the_remainder_on_the_card() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val hold = SettleHold(quietCenter(), seconds = 60.0, write = { _, s -> SettleAll.record(store, s) }, written = {}, scope = scope, clock = { 0L })
        val section = SettleAll.sections(store).first()
        val entry = section.lines.first().entries.first()
        assertEquals("you owe Ana 32.00", ShareOut(ana, -3_200), entry)
        val minor = (OtherAmount.verdict("12.00", 3_200, "EUR", true, "Ana") {} as? OtherAmount.Verdict.Settles)?.minor
            ?: return fail("12.00 settles")
        val partial = SettleAll.settlement(entry, "EUR", named, minor)
        assertEquals(1_200L, partial.amountMinor)
        assertEquals("you pay", named.selfId, partial.from)
        hold.hold(partial, "Ana", store)
        assertEquals("nothing written while held", 0, store.loadSettlements().size)
        val held = hold.counting { SettleAll.sections(store, it) }.first()
        assertEquals("the remainder, 20.00, at once", listOf(ShareOut(ana, -2_000)), held.lines.first().entries)
        assertEquals("the card stays", SettleState.OPEN, held.state)
        hold.commit()
        assertEquals("the partial, written once", listOf(partial), store.loadSettlements())
        val after = hold.counting { SettleAll.sections(store, it) }.first()
        assertEquals(listOf(ShareOut(ana, -2_000)), after.lines.first().entries)
    }

    // ---- The hold and Undo (decision 182) ----

    /** Undo writes nothing: the write count stays 0, the store stays empty, the card comes back, "Not recorded." shows. */
    @Test
    fun undo_writes_nothing() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val center = quietCenter()
        var writes = 0
        var written = 0
        val hold = SettleHold(center, 60.0, write = { _, _ -> writes += 1 }, written = { written += 1 }, scope = scope, clock = { 0L })
        val s = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named)
        hold.hold(s, "Ana", store)
        assertEquals(DoneMoments.lineWithAction("Settled with Ana. No money moved.", "Undo", 60.0), center.current)
        assertEquals("shown as settled at once", SettleState.ALL_SETTLED, hold.counting { SettleAll.sections(store, it) }.first().state)
        center.act(center.serial)
        assertNull(hold.pending)
        assertEquals(DoneMoments.line("Not recorded."), center.current)
        assertEquals("the card is back", listOf(ShareOut(ana, -3_200)), hold.counting { SettleAll.sections(store, it) }.first().lines.first().entries)
        hold.commit()
        assertEquals("Undo wrote nothing, and nothing is left to land", 0, writes)
        assertEquals(0, written)
        assertEquals(0, store.loadSettlements().size)
    }

    /** The window ending writes once (the real store), the Undo toast goes, and a late Undo does nothing. */
    @Test
    fun the_timeout_writes_once() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val center = quietCenter()
        var writes = 0
        val hold = SettleHold(center, 0.2, write = { _, s -> writes += 1; SettleAll.record(store, s) }, written = {}, scope = scope, clock = { 0L })
        val s = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named)
        hold.hold(s, "Ana", store)
        val serial = center.serial
        Thread.sleep(800)
        assertEquals("written once, when the window ended", 1, writes)
        assertNull(hold.pending)
        assertNull("its Undo went with it", center.current)
        assertEquals(listOf(s), store.loadSettlements())
        center.act(serial)
        hold.undo(1)
        hold.commit()
        assertEquals("a late Undo or another landing does nothing", 1, writes)
        assertEquals(1, store.loadSettlements().size)
    }

    /** Leaving the front lands it (MainActivity's onStop calls `commit`), once; so does the screen going away. */
    @Test
    fun leaving_the_front_lands_the_held_settle() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val writes = mutableListOf<SettlementDTO>()
        val hold = SettleHold(quietCenter(), 60.0, write = { _, s -> writes.add(s) }, written = {}, scope = scope, clock = { 0L })
        val s = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named)
        hold.hold(s, "Ana", store)
        assertEquals("nothing before", emptyList<SettlementDTO>(), writes)
        hold.commit()
        hold.commit()
        assertEquals("once", listOf(s), writes)
        val root = src("AppRoot.kt")
        val stop = root.substringAfter("override fun onStop() {").substringBefore("\n    }\n")
        assertTrue("onStop lands it", "if (!isChangingConfigurations) SettleHold.shared.commit()" in stop)
        val all = src("SettleAll.kt")
        val lands = all.substringAfter("fun LandsHeldSettle() {").substringBefore("\n}\n")
        assertTrue("going away lands it (never a re-creation)", "onDispose { if (context.findActivity()?.isChangingConfigurations != true) SettleHold.shared.commit() }" in lands)
        assertEquals("Settle up", 1, count(all.substringAfter("fun SettleAllScreen("), "LandsHeldSettle()"))
        assertEquals("the group page", 1, count(src("OverviewTab.kt"), "LandsHeldSettle()"))
    }

    /** The next Settle tap lands the one held (one at a time), and the new one is held in its place. */
    @Test
    fun the_next_settle_lands_the_held_one() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val writes = mutableListOf<SettlementDTO>()
        val hold = SettleHold(quietCenter(), 60.0, write = { _, s -> writes.add(s) }, written = {}, scope = scope, clock = { 0L })
        val first = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named, 1_000)
        val second = SettleAll.settlement(ShareOut(ana, -2_200), "EUR", named, 500)
        hold.hold(first, "Ana", store)
        hold.hold(second, "Ana", store)
        assertEquals("the first landed at the second tap", listOf(first), writes)
        assertEquals("one held at a time", listOf(second), hold.held)
        hold.undo(1)
        assertEquals("the first's Undo no longer reaches anything", listOf(second), hold.held)
        hold.undo(2)
        assertEquals("the second was undone: never written", listOf(first), writes)
    }

    /** CLAUDE.md 10: core checks a settle before it is held; a refusal holds nothing, writes nothing, posts nothing. */
    @Test
    fun core_refuses_before_anything_is_held() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val center = quietCenter()
        var writes = 0
        val hold = SettleHold(center, 60.0, write = { _, _ -> writes += 1 }, written = {}, scope = scope, clock = { 0L })
        val zero = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named, 0)
        assertTrue(runCatching { hold.hold(zero, "Ana", store) }.isFailure)
        assertNull(hold.pending)
        assertNull(center.current)
        hold.commit()
        assertEquals(0, writes)
    }

    /** A write that fails says so and leaves nothing held. */
    @Test
    fun a_failed_write_says_so() {
        val store = tempStore()
        val (named, ana) = paris(store)
        var written = 0
        val hold = SettleHold(
            quietCenter(), 60.0, write = { _, _ -> throw uniffi.skipti_core.CoreException.InvalidInput("disk") },
            written = { written += 1 }, scope = scope, clock = { 0L },
        )
        hold.hold(SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named), "Ana", store)
        val before = hold.revision
        hold.commit()
        assertNull(hold.pending)
        assertTrue(hold.failure?.startsWith("Could not record the settlement.") == true)
        assertEquals("no push for what was not written", 0, written)
        assertTrue("the screens read again: the card comes back", hold.revision > before)
    }

    /** Both settle paths hold through the one hold, and the one append is `SettleAll.record`; both screens count the held
     *  settle (under the hold's lock) and read again when it changes. */
    @Test
    fun both_screens_hold_and_count_the_held_settle() {
        val all = src("SettleAll.kt")
        val screen = all.substringAfter("fun SettleAllScreen(")
        assertTrue("hold.counting { held -> SettleAll.sections(store, held) }" in screen)
        assertTrue("LaunchedEffect(vaultPath, SyncStatus.revision, hold.revision)" in screen)
        assertTrue("the sheet is owned by the screen", "otherAmount?.let { draft ->" in screen)
        val page = src("OverviewTab.kt")
        assertTrue("SettleHold.shared.counting { held -> (store.loadSettlements() + held).map { it.toInput() } }" in page)
        assertTrue("LaunchedEffect(vaultPath, groupId, SyncStatus.revision, requestIds, SettleHold.shared.revision)" in page)
        val hold = all.substringAfter("class SettleHold(").substringBefore("\n}\n")
        assertTrue(
            "the write's own check, the old failure cleared, then the held one lands",
            "store.checkSettlement(settlement)\n        failure = null\n        commit()" in hold,
        )
        assertTrue("the live write is the one append", "{ path, settlement -> SettleAll.record(LedgerStore(path), settlement) }" in hold)
        assertTrue("written and dropped under the lock", "val failed = synchronized(lock) {\n            pending = null\n            runCatching { write(p.path, p.settlement) }" in hold)
    }

    // ---- D-247 fix wave: one check, a kept failure, the removal, the live cap, the screen reader's window ----

    /** I1 (a): the landing at the next Settle tap fails: its words stay (the new settle is held, the failure is not wiped
     *  by the tap that caused it); the person's next tap clears it before its own landing, which succeeds. */
    @Test
    fun a_failed_landing_at_the_next_settle_keeps_its_words() {
        val store = tempStore()
        val (named, ana) = paris(store)
        var calls = 0
        val hold = SettleHold(quietCenter(), 60.0, write = { _, _ ->
            calls += 1
            if (calls == 1) throw uniffi.skipti_core.CoreException.InvalidInput("disk")
        }, written = {}, scope = scope, clock = { 0L })
        val first = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named, 1_000)
        val second = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named, 500)
        hold.hold(first, "Ana", store)
        assertNull(hold.failure)
        hold.hold(second, "Ana", store)
        assertEquals("the first landed (and failed) at the second tap", 1, calls)
        assertEquals("the second is held", listOf(second), hold.held)
        assertTrue("the first's failure is kept", hold.failure?.startsWith("Could not record the settlement.") == true)
        hold.hold(first, "Ana", store)
        assertEquals(2, calls)
        assertNull("the next tap cleared it; its own landing wrote", hold.failure)
    }

    /** I1 (c): the hold runs the write's own check ([LedgerStore.checkSettlement]: the place, then core): a group whose
     *  place this phone lost, and a payment whose two ends become one id, are refused at the tap: nothing held, nothing
     *  posted, nothing written; core's bare check alone would have let both through. */
    @Test
    fun the_hold_runs_the_writes_own_check() {
        val f = FounderVault
        val lost = f.lost(tempStore())
        val paid = SettlementDTO(f.hex(1), f.hex(3), 600, "INR", f.hex(9))
        uniffi.skipti_core.ffiCheckSettlement(paid.toInput()) // not vacuous: core alone takes it
        val same = f.p(tempStore(), 2)
        val toSelf = SettlementDTO(f.hex(1), f.hex(2), 100, "INR", f.hex(9))
        uniffi.skipti_core.ffiCheckSettlement(toSelf.toInput()) // not vacuous: core alone takes it
        for ((store, s, refusal) in listOf(Triple(lost, paid, "lost"), Triple(same, toSelf, "same"))) {
            val center = quietCenter()
            var writes = 0
            val hold = SettleHold(center, 60.0, write = { _, _ -> writes += 1 }, written = {}, scope = scope, clock = { 0L })
            val error = runCatching { hold.hold(s, "Bo", store) }.exceptionOrNull()
            if (refusal == "lost") assertTrue("$error", error is Placing.PlaceLost) else assertTrue("$error", error is Placing.SameEnds)
            assertNull(refusal, hold.pending)
            assertNull("no toast: $refusal", center.current)
            hold.commit()
            assertEquals(refusal, 0, writes)
        }
        // The append runs the same check (one function, so the hold and the write cannot disagree).
        val ledger = src("LedgerStore.kt")
        assertEquals("appendSettlement's check", 1, count(ledger, "val placing = checkSettlement(settlement)"))
        assertEquals("the check lives in checkSettlement alone", 1, count(ledger, "ffiCheckSettlement(placing.settlement(settlement).toInput())"))
    }

    /** I1 (b): the group page shows a held settle's failure in words, as Settle up does (source: the page's own error line). */
    @Test
    fun the_group_page_says_a_held_settle_failed() {
        assertEquals(1, count(src("OverviewTab.kt"), "(error ?: SettleHold.shared.failure)?.let {"))
        assertEquals(1, count(src("SettleAll.kt"), "(error ?: hold.failure)?.let {"))
    }

    /** I2: a settle held for Undo, then the removal of the same person: the removal lands the hold first, and Settle
     *  first's payments, worked out before the hold landed, are worked out again: the debt is recorded once. */
    @Test
    fun a_held_settle_then_settle_first_records_the_debt_once() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val group = named.group.id
        val hold = SettleHold(quietCenter(), 60.0, write = { _, s -> SettleAll.record(store, s) }, written = {}, scope = scope, clock = { 0L })
        // What Settle first showed before the settle was held: you pay Ana 32.00.
        val shown = Removal.settleFirst(Removal.lines(store, group, ana), ana, group)
        assertEquals(listOf(SettlementDTO(named.selfId, ana, 3_200, "EUR", group)), shown)
        val held = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named)
        hold.hold(held, "Ana", store)
        val pay = Removal.settleFirstLanding(store, shown, ana, group, started = false, hold = hold)
        assertEquals("the held settle landed: nothing left to settle first", emptyList<SettlementDTO>(), pay)
        pay.forEach { store.appendSettlement(it) }
        assertEquals("the debt recorded once", listOf(held), store.loadSettlements())
        assertEquals("Ana is square: never paid twice", emptyMap<String, Long>(), Removal.plan(Removal.lines(store, group, ana), ana))
        // Nothing held: the payments shown are the payments recorded.
        assertEquals(shown, Removal.settleFirstLanding(store, shown, ana, group, started = false, hold = hold))

        // The flow's read lands the hold too, before it reads the balance (a partial here).
        val store2 = tempStore()
        val (named2, ana2) = paris(store2)
        val hold2 = SettleHold(quietCenter(), 60.0, write = { _, s -> SettleAll.record(store2, s) }, written = {}, scope = scope, clock = { 0L })
        hold2.hold(SettleAll.settlement(ShareOut(ana2, -3_200), "EUR", named2, 1_200), "Ana", store2)
        assertTrue("landed", hold2.commit())
        val lines = Removal.lines(store2, named2.group.id, ana2)
        assertEquals("the balance after the held 12.00", mapOf("EUR" to 2_000L), Removal.plan(lines, ana2))
        assertEquals(listOf(2_000L), Removal.settleFirst(lines, ana2, named2.group.id).map { it.amountMinor })
        // Both of the flow's reads commit on the main thread before reading, and Settle first's tap goes through the
        // landing (source: the reads are LaunchedEffects).
        val flow = src("RemovalFlow.kt").substringAfter("fun RemovalFlowScreen(")
        assertEquals("the flow's two reads", 2, count(flow, "SettleHold.shared.commit()\n        val result = withContext(Dispatchers.IO) {"))
        assertEquals("Settle first", 1, count(flow, "Removal.settleFirstLanding(LedgerStore(vaultPath), settlements, mid, gid, started = (held ?: settle)?.started == true)"))
        assertEquals(1, count(flow, "append = { i -> LedgerStore(vaultPath).appendSettlement(pay[i]) },"))
    }

    /** Review M3: Settle on the sheet checks the cap against the card's LIVE debt: a payment that arrived meanwhile and
     *  shrank it refuses in the cap's words; the card gone (or turned the other way) refuses too; within it settles. */
    @Test
    fun the_cap_is_checked_against_the_live_debt_at_settle() {
        val store = tempStore()
        val (named, ana) = paris(store)
        val group = named.group.id
        fun live() = SettleAll.liveEntry(SettleAll.sections(store), group, "EUR", ana, youOwe = true)
        assertEquals("the sheet opened on 32.00", ShareOut(ana, -3_200), live())
        assertEquals(
            "the field, against the debt it opened on",
            OtherAmount.Verdict.Settles(2_500), OtherAmount.verdict("25.00", 3_200, "EUR", true, "Ana") {},
        )
        // A payment of 12.00 arrives (a sync) while the sheet is open.
        store.appendSettlement(SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named, 1_200))
        val shrunk = live()
        assertEquals(ShareOut(ana, -2_000), shrunk)
        assertEquals(
            "refused at the tap, in the cap's words",
            OtherAmount.Verdict.TooMuch("That is more than you owe Ana."), OtherAmount.atSettle(2_500, shrunk, true, "Ana"),
        )
        assertEquals(OtherAmount.Verdict.Settles(2_000), OtherAmount.atSettle(2_000, shrunk, true, "Ana"))
        assertEquals("the card is gone", OtherAmount.Verdict.TooMuch("That is more than Ana owes."), OtherAmount.atSettle(1, null, false, "Ana"))
        assertNull("the other way round is not the same card", SettleAll.liveEntry(SettleAll.sections(store), group, "EUR", ana, youOwe = false))
        assertNull(SettleAll.liveEntry(SettleAll.sections(store), group, "INR", ana, youOwe = true))
        // The sheet shows the tap's refusal as the cap's line; the field's own line comes first.
        assertEquals("r", OtherAmount.capWords(OtherAmount.Verdict.Settles(1), "r", rested = false))
        assertEquals("t", OtherAmount.capWords(OtherAmount.Verdict.TooMuch("t"), "r", rested = false))
        assertNull(OtherAmount.capWords(OtherAmount.Verdict.Settles(1), null, rested = true))
        val all = src("SettleAll.kt")
        assertEquals(1, count(all, "when (val verdict = OtherAmount.atSettle(minor, live, draft.youOwe, draft.other)) {"))
        assertEquals(1, count(all, "(verdict as? OtherAmount.Verdict.Settles)?.let { refused = onSettle(it.minor) }"))
    }

    /** Decision 201: with TalkBack running the hold lasts 12 s instead of 5, the toast stays as long, and the announcement
     *  names the Undo and its time. */
    @Test
    fun the_hold_is_longer_with_a_screen_reader() {
        assertEquals(5.0, SettleHold.window(false), 0.0)
        assertEquals(DoneCenter.ACTION_SECONDS, SettleHold.window(false), 0.0)
        assertEquals(12.0, SettleHold.window(true), 0.0)
        assertFalse("no app context (a JVM test): no screen reader", ScreenReader.running())
        val store = tempStore()
        val (named, ana) = paris(store)
        val s = SettleAll.settlement(ShareOut(ana, -3_200), "EUR", named)
        for ((reader, seconds) in listOf(true to 12.0, false to 5.0)) {
            val center = quietCenter()
            val hold = SettleHold(center, screenReader = { reader }, write = { _, _ -> }, written = {}, scope = scope, clock = { 0L })
            hold.hold(s, "Ana", store)
            val line = (center.current as DoneMoment.Small).line
            assertEquals(seconds, line.actionSeconds, 0.0)
            assertEquals("the toast lasts the hold's window", seconds, DoneMoments.toastSeconds(line), 0.0)
            assertEquals("Settled with Ana. No money moved. Undo is available for ${seconds.toInt()} seconds.", DoneCopy.spoken(line))
            assertEquals("the host's live region says the same", DoneCopy.spoken(line), DoneCopy.spoken(center.current!!))
            hold.undo(1)
        }
        assertEquals("no action: nothing added", "Name saved", DoneCopy.spoken(DoneLine("Name saved", emptyList())))
        assertEquals("Undo is available for 12 seconds.", DoneCopy.actionSpoken("Undo", "12"))
        val all = src("SettleAll.kt")
        assertEquals(1, count(all, "private val screenReader: () -> Boolean = ScreenReader::running,"))
        assertEquals(1, count(all, "val seconds = this.seconds ?: window(screenReader())"))
    }

    /** Review M1: SKIPTI leaving the front lands the held settle at ON_PAUSE too (the twin of iOS's inactive), never on a
     *  re-creation (source: the activity's callbacks). */
    @Test
    fun the_pause_lands_the_held_settle() {
        val root = src("AppRoot.kt")
        val pause = root.substringAfter("override fun onPause() {").substringBefore("\n    }\n")
        assertTrue("onPause lands it", "if (!isChangingConfigurations) SettleHold.shared.commit()" in pause)
        assertTrue("before super.onPause()", pause.indexOf("SettleHold.shared.commit()") < pause.indexOf("super.onPause()"))
    }

    // ---- The settled words and the count (decision 168) ----

    private fun block(code: String, settled: Boolean) =
        SettledProgressOut(code, if (settled) 100 else 0, if (settled) 0 else 100, if (settled) 10_000u else 0u, settled)

    /** "All settled" only from core's allSettled; you square while others owe is its own words; you owing is the cards. */
    @Test
    fun all_settled_only_when_core_says_so() {
        assertEquals(SettleState.OPEN, SettleState.of(false, listOf(block("INR", false))))
        assertEquals("your lines decide the cards", SettleState.OPEN, SettleState.of(false, listOf(block("INR", true))))
        assertEquals(SettleState.ALL_SETTLED, SettleState.of(true, listOf(block("INR", true))))
        assertEquals(SettleState.ALL_SETTLED, SettleState.of(true, listOf(block("INR", true), block("USD", true))))
        assertEquals(SettleState.YOU_SETTLED, SettleState.of(true, listOf(block("INR", false))))
        assertEquals("one currency still open is not all settled", SettleState.YOU_SETTLED, SettleState.of(true, listOf(block("INR", true), block("USD", false))))
        assertEquals("nothing recorded yet: nobody owes", SettleState.ALL_SETTLED, SettleState.of(true, emptyList()))
        assertTrue("the screen shows the you-only words", "SettleAllCopy.youSettled(named.group.name)" in src("SettleAll.kt"))
    }

    /** D-247 sabotage pin (row A9): each settled state draws its own two lines on the branch of core's state: "all
     *  settled" with "Nobody owes anybody. Nice.", "You're settled in" with "You don't owe anything here." (never the
     *  all-settled detail under the you-only title). The twin of iOS's testEachSettledStateDrawsItsOwnWords. */
    @Test
    fun each_settled_state_draws_its_own_words() {
        val screen = src("SettleAll.kt").substringAfter("fun SettleAllScreen(").substringBefore("fun OtherAmountSheet(")
        // D-250 fix wave (twin 5): the panel is a MovingRows of the settled state (it fades in and out, as iOS's).
        assertEquals(1, count(screen, "val all = state == SettleState.ALL_SETTLED"))
        assertEquals("the title", 1, count(screen, "if (all) SettleAllCopy.settled(named.group.name) else SettleAllCopy.youSettled(named.group.name),"))
        assertEquals("the detail", 1, count(screen, "if (all) SettleAllCopy.SETTLED_DETAIL else SettleAllCopy.YOU_SETTLED_DETAIL,"))
        assertEquals("the all-settled detail only on its own branch", 1, count(screen, "SettleAllCopy.SETTLED_DETAIL"))
    }

    /** D-247 sabotage pin (row A10): the sheet's Settle stays disabled until the field holds an amount within the debt (the
     *  verdict settles) and while a tap's refusal stands; a tap settles only a Settles verdict. The twin of iOS's
     *  testTheSheetsSettleWaitsForAnAmountWithinTheDebt. */
    @Test
    fun the_sheets_settle_waits_for_an_amount_within_the_debt() {
        val sheet = src("SettleAll.kt").substringAfter("fun OtherAmountSheet(")
        assertEquals(1, count(sheet, "PrimaryAction(SettleAllCopy.SETTLE, inDark, enabled = OtherAmount.tappable(verdict) && refused == null) {"))
        assertEquals("Settle is the sheet's one gated control", 1, count(sheet, "enabled = "))
        assertEquals(1, count(sheet, "(verdict as? OtherAmount.Verdict.Settles)?.let { refused = onSettle(it.minor) }"))
        // D-248 fix wave (review M1): a tap on what is not an amount records nothing and says why at once.
        assertEquals(1, count(sheet, "if (verdict is OtherAmount.Verdict.NotAnAmount) rested = true"))
        assertFalse("empty, zero", OtherAmount.settles(OtherAmount.Verdict.Incomplete))
        assertFalse(OtherAmount.tappable(OtherAmount.Verdict.Incomplete))
        assertFalse("more than owed", OtherAmount.settles(OtherAmount.Verdict.TooMuch(SettleAllCopy.moreThanDebt(false, "Ana"))))
        assertTrue(OtherAmount.settles(OtherAmount.Verdict.Settles(1)))
    }

    // ---- The creator fall-back (decision 170) ----

    /** A group this phone created reads as yours to run until the cloud's roles arrive: the owner's options, never "Ask to
     *  be removed". A group made elsewhere, or once the roles are known, reads by the cloud's answer. */
    @Test
    fun the_creator_fall_back_is_only_for_this_phones_group_until_roles_arrive() {
        val me = "1".repeat(32)
        val ana = "2".repeat(32)
        val members = listOf(MemberWire(me, "Prithvi"), MemberWire(ana, "Ana"))
        val claims = mapOf(ana to "a".repeat(32))
        fun rows(roles: RoleRules.Roles?, createdHere: Boolean) =
            PeopleRoles.rows(members, me, me, claims, roles, createdHere = createdHere)
        val fallBack = rows(null, createdHere = true)
        assertEquals(RoleRules.Role.OWNER, PeopleRoles.viewerRole(fallBack))
        val mine = fallBack.first { it.isSelf }
        val hers = fallBack.first { !it.isSelf }
        assertFalse("never 'Ask to be removed'", mine.offer.askSelf)
        assertTrue("the owner's remove", hers.offer.remove)
        assertTrue(hers.offer.makeAdmin)
        assertTrue(RoleRules.canDelete(PeopleRoles.viewerRole(fallBack)))
        // Not this phone's group: a member until the roles say otherwise.
        val joined = rows(null, createdHere = false)
        assertEquals(RoleRules.Role.MEMBER, PeopleRoles.viewerRole(joined))
        assertTrue(joined.first { it.isSelf }.offer.askSelf)
        // Roles known: the cloud wins, whoever created it.
        val theirs = RoleRules.Roles(claims[ana], emptyList())
        val known = rows(theirs, createdHere = true)
        assertEquals("the cloud says Ana owns it now", RoleRules.Role.MEMBER, PeopleRoles.viewerRole(known))
        assertTrue(known.first { it.isSelf }.offer.askSelf)
        assertFalse(CreatorFallBack.applies(theirs, createdHere = true))
        assertFalse(CreatorFallBack.applies(null, createdHere = false))
        assertTrue(CreatorFallBack.applies(null, createdHere = true))
        // A copy offers nothing, fall-back or not.
        val copy = PeopleRoles.rows(members, me, me, claims, null, readOnly = true, createdHere = true)
        assertFalse(copy.any { it.offer.remove || it.offer.askSelf })
    }

    /** "This phone created it" is a create record authored by THIS device: a group created here is in; a group whose
     *  create came from another device (a joined group) is not; a vault with no records holds none. */
    @Test
    fun created_here_reads_only_this_devices_creates() {
        val store = tempStore()
        assertEquals("an empty vault", emptySet<String>(), LedgerStore.Sync.createdHere(store))
        store.setYourName("Prithvi")
        val made = store.createGroup("Goa trip", "INR", listOf("Rahul"))
        val other = "e".repeat(32)
        val joined = "9".repeat(32)
        store.applyRecords(
            listOf(
                LedgerStore.groupOpRecord(
                    uniffi.skipti_core.GroupOpIn.Create(joined, "Tim's flat", "INR", listOf(uniffi.skipti_core.GroupOpMemberIn("7".repeat(32), "Tim"))),
                    uniffi.skipti_core.HlcStamp(1uL, 0u), other,
                ),
            ),
        )
        val here = LedgerStore.Sync.createdHere(store)
        assertEquals("only the group this device created", setOf(made.group.id), here)
        assertFalse("another device's create", joined in here)
        assertTrue(GroupPeople.load(store, made.group.id).createdHere)
        assertNotNull(store.allRecords().firstOrNull { it.authorDeviceId == other })
    }

    /** Display only: the three screens that offer role actions pass the fall-back's input, and nothing else changes (the
     *  calls still go to the Functions). */
    @Test
    fun the_fall_back_reaches_every_roles_screen() {
        for (file in listOf("OverviewTab.kt", "GroupScreens.kt", "GroupSettings.kt")) {
            assertEquals(file, 1, count(src(file), "createdHere = people.createdHere"))
        }
    }
}
