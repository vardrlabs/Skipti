package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiSettlementPayload

/**
 * §5.8's guards, against the REAL core and the REAL skrin vault on the host
 * dylib. THE LEGAL BOUNDARY: a settlement is a RECORD that a debt was paid,
 * never a movement of money (ADR-0008, §13.2): the tests pin the ledger
 * semantics (the row leaves the RECOMPUTED plan) and the straumr record
 * path (written, closed, reopened, still there), never a UI flag.
 */
class SettleTest {

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
            Files.createTempDirectory("settle-test").resolve("ledger-vault.db").toString()

        fun store(): LedgerStore = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        val opener: () -> uniffi.skipti_core.SkiptiVault = { SkiptiVault.open(path, keystore) }

        /** Reads the typed record bytes through a FRESH open of the same
         *  vault: proving what was PERSISTED, not what a handle holds. */
        fun readRecords(): List<uniffi.skipti_core.RecordWire> {
            val vault = SkiptiVault.open(path, keystore)
            try {
                return vault.allRecords()
            } finally {
                try { vault.closeVault() } catch (_: Exception) {}
            }
        }
    }

    /** The recompute the SCREEN does: the SAME function (SettleUi.recomputePlan),
     *  so a settlement-shaped bug or a Kotlin patch turns this red. */
    private fun recompute(fixture: VaultFixture): List<uniffi.skipti_core.BalanceLineOut> {
        val store = fixture.store()
        val expenses = store.loadExpenses()
        val settlements = store.loadSettlements()
        val groups = store.loadGroups()
        val group = groups.firstOrNull { g -> expenses.any { it.group == g.id } }
        return SettleUi.recomputePlan(expenses, settlements, emptyList(), group?.id, BalanceFixtures.ada)
    }

    @Test
    fun settling_removes_the_debt_from_the_recomputed_plan() {
        val fixture = VaultFixture()
        val store = fixture.store()
        store.loadGroupsOrSeed()
        // You paid $25.00 for you and Rahul: Rahul owes you 1,250.
        store.appendExpense(
            AddExpenseUi.buildExpense(
                amountText = "25.00", currency = "USD", note = "Taxi",
                category = null,
                participants = setOf(BalanceFixtures.ada, BalanceFixtures.rahul),
                draft = AddExpenseUi.SplitDraft.Equal,
                payer = BalanceFixtures.ada,
                groupId = store.loadGroups()[0].id,
            )
        )

        val before = recompute(fixture)
        val entry = before.first { it.currencyCode == "USD" }
            .entries.first { it.id == BalanceFixtures.rahul }
        assertEquals(1_250L, entry.minorUnits)

        // §5.8: record the settlement the only way the app knows how.
        val persisted = store.appendSettlement(
            SettlementDTO(
                from = BalanceFixtures.rahul,
                to = BalanceFixtures.ada,
                amountMinor = 1_250L,
                currencyCode = "USD",
                group = store.loadGroups()[0].id,
            ),
            nowMs = 1_756_000_000_000,
        )
        assertEquals(1, persisted.size)

        // THE SETTLED STATE IS THE ROW LEAVING THE RECOMPUTED PLAN:
        // computed from the ledger, never a UI flag.
        val after = recompute(fixture)
        val usd = after.firstOrNull { it.currencyCode == "USD" }
        assertTrue(
            "the settled debt leaves the plan entirely",
            usd == null || usd.entries.none { it.id == BalanceFixtures.rahul },
        )
    }

    @Test
    fun straumr_record_round_trips_through_close_and_reopen() {
        val fixture = VaultFixture()
        val store = fixture.store()
        store.loadGroupsOrSeed()
        val group = store.loadGroups()[0].id
        store.appendSettlement(
            SettlementDTO(
                from = BalanceFixtures.rahul,
                to = BalanceFixtures.ada,
                amountMinor = 1_250L,
                currencyCode = "USD",
                group = group,
            ),
            nowMs = 1_756_000_000_000,
        )
        // A NEW handle reads the persisted record bytes.
        val records = fixture.readRecords()
        assertEquals(1, records.size)
        val record = records[0]
        assertEquals(2u.toUByte(), record.recordType) // straumr RecordType.settlement
        // ADR-0034: the author is THIS DEVICE's 16-byte id (never the
        // payer's participant id: the old placeholder conflated the two),
        // and the HLC carries the creation time with counter 0 (first
        // record this device has ever stamped).
        val device = LedgerStore.Sync.deviceId(store)
        assertEquals(device, record.authorDeviceId)
        assertEquals(device, record.hlcDeviceId)
        assertEquals(1_756_000_000_000uL, record.hlcMillis)
        // D-237 (decision 132): the counter is this stamp's random step, under 2^20 (core's STEP_BITS), no longer 0.
        assertTrue("the counter is the random step: ${record.hlcCounter}", record.hlcCounter < (1u shl 20))
        // ADR-0034: the blob is the record's OWN DTO JSON (the app's
        // cross-shell envelope entry), sealed before it ever leaves, so a
        // receiving device decodes it back into the same settlement.
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            SettlementDTO.serializer(), record.blob.decodeToString(),
        )
        assertEquals(BalanceFixtures.rahul, decoded.from)
        assertEquals(BalanceFixtures.ada, decoded.to)
        assertEquals(1_250L, decoded.amountMinor)
        assertEquals("USD", decoded.currencyCode)
        assertEquals(group, decoded.group)
    }

    /** Kept for the reopen semantics the round-trip test relies on: the
     *  fixture's ONE keystore answers every open, so reopens unlock. */

    @Test
    fun settlement_payload_refuses_what_a_ledger_must() {
        // Zero amount, self-payment, empty id: the record path refuses
        // before anything is written. These are §5.8's own rules.
        for (bad in listOf(
            Triple(BalanceFixtures.ada, BalanceFixtures.ada, 1_000L), // self
            Triple(BalanceFixtures.ada, BalanceFixtures.rahul, 0L), // zero
            Triple(BalanceFixtures.ada, BalanceFixtures.rahul, -5L), // negative
        )) {
            try {
                ffiSettlementPayload(
                    from = bad.first,
                    to = bad.second,
                    amountMinor = bad.third,
                    currencyCode = "USD",
                    timestampMs = 1uL,
                    group = null,
                )
                fail("settlement $bad must be refused")
            } catch (_: Exception) {
                // exactly the refusal appendSettlement surfaces
            }
        }
    }

    @Test
    fun plan_is_core_simplify_where_naive_pairwise_needs_more_transfers() {
        // The chained ledger: rahul paid for priya, priya paid for amit.
        // Naive pairwise settles TWO debts (priya→rahul, amit→priya);
        // core's nets are rahul +1000, priya 0, amit −1000: the plan is
        // ONE transfer, amit→rahul, and n−1 = 2 is an upper bound that is
        // actually beaten. This is the §5.6 property pinned, not
        // reimplemented in Kotlin.
        val fixture = VaultFixture()
        val store = fixture.store()
        store.loadGroupsOrSeed()
        val group = store.loadGroups()[0].id
        store.appendExpense(
            ExpenseDTO(
                payer = BalanceFixtures.rahul,
                paidMinor = 1_000L,
                currencyCode = "INR",
                group = group,
                shares = listOf(ExpenseDTO.ShareDTO(id = BalanceFixtures.priya, amountMinor = 1_000L)),
            )
        )
        store.appendExpense(
            ExpenseDTO(
                payer = BalanceFixtures.priya,
                paidMinor = 1_000L,
                currencyCode = "INR",
                group = group,
                shares = listOf(ExpenseDTO.ShareDTO(id = BalanceFixtures.amit, amountMinor = 1_000L)),
            )
        )
        val lines = recompute(fixture)
        val inr = lines.first { it.currencyCode == "INR" }
        assertEquals(1, inr.transfers.size)
        val t = inr.transfers[0]
        assertEquals(BalanceFixtures.amit, t.from)
        assertEquals(BalanceFixtures.rahul, t.to)
        assertEquals(1_000L, t.amountMinor)
        // And it conserved: applying the plan zeroes every ABSOLUTE net.
        // The nets come from §5.6's formula over the known ledger (payer
        // gains, sharers owe): the test applying it is what makes this a
        // property pin on core rather than a restatement.
        val nets = mapOf(
            BalanceFixtures.ada to 0L,
            BalanceFixtures.rahul to 1_000L,
            BalanceFixtures.priya to 0L,
            BalanceFixtures.amit to -1_000L,
        )
        assertTrue(
            "the plan must conserve: ${inr.transfers}",
            SettleUi.planConserves(nets, inr.transfers),
        )
        // The guard's NEGATIVE face: a vacuous always-true check passes the
        // happy property, so the check must also REJECT broken plans:
        // nets left open, and a plan with a wrong amount.
        assertFalse(
            "nets left open must not pass the conservation check",
            SettleUi.planConserves(nets, emptyList()),
        )
        assertFalse(
            "a wrong-amount transfer must not pass the conservation check",
            SettleUi.planConserves(
                nets,
                listOf(uniffi.skipti_core.TransferOut(BalanceFixtures.amit, BalanceFixtures.rahul, 999L)),
            ),
        )
    }

    @Test
    fun verbs_keep_their_own_progression() {
        // §11.6: Settle → Settling…; Mark received → Receiving…. Never one
        // verb morphing into the other's (D-49's audit catch).
        assertEquals("Settle", SettleUi.buttonLabel(youOwe = true, settling = false))
        assertEquals("Settling…", SettleUi.buttonLabel(youOwe = true, settling = true))
        assertEquals("Mark received", SettleUi.buttonLabel(youOwe = false, settling = false))
        assertEquals("Receiving…", SettleUi.buttonLabel(youOwe = false, settling = true))
    }

    @Test
    fun plan_rows_are_sentence_case_and_agree_with_the_spoken_label() {
        // "you pay Rahul": the viewer lowercase inside a sentence; the
        // spoken label says the SAME words ("you pay Rahul.").
        assertEquals(
            "you pay Rahul",
            SettleUi.planRow(BalanceFixtures.ada, BalanceFixtures.rahul, BalanceFixtures.ada, BalanceFixtures.names),
        )
        assertEquals(
            "you pay Rahul.",
            SettleUi.planSpoken(BalanceFixtures.ada, BalanceFixtures.rahul, BalanceFixtures.ada, BalanceFixtures.names),
        )
        assertEquals(
            "Rahul pays you",
            SettleUi.planRow(BalanceFixtures.rahul, BalanceFixtures.ada, BalanceFixtures.ada, BalanceFixtures.names),
        )
        assertEquals(
            "Rahul pays you.",
            SettleUi.planSpoken(BalanceFixtures.rahul, BalanceFixtures.ada, BalanceFixtures.ada, BalanceFixtures.names),
        )
        // The explainer rides everything, verbatim.
        assertEquals(
            "Marks this as paid here. It does not move money.",
            SettleUi.EXPLAINER,
        )
    }

    @Test
    fun settlement_rows_are_viewer_framed_both_directions() {
        val entry = { minor: Long ->
            uniffi.skipti_core.ShareOut(id = BalanceFixtures.rahul, minorUnits = minor)
        }
        assertEquals(
            "You owe Rahul",
            SettleUi.debtLabel(entry(-1_250L), BalanceFixtures.ada, BalanceFixtures.names),
        )
        assertEquals(
            "You owe Rahul.",
            SettleUi.debtSpoken(entry(-1_250L), BalanceFixtures.ada, BalanceFixtures.names),
        )
        assertEquals(
            "Rahul owes you",
            SettleUi.debtLabel(entry(1_250L), BalanceFixtures.ada, BalanceFixtures.names),
        )
        assertEquals(
            "Rahul owes you.",
            SettleUi.debtSpoken(entry(1_250L), BalanceFixtures.ada, BalanceFixtures.names),
        )
    }
}
