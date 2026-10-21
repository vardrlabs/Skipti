package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * D-151: the kv ledger has ONE byte form, the twin of iOS's
 * ProjectionBytesTests. The sync round trip (SyncProofLiveTest) compares the
 * ledger's BYTES after a restore, and runs only with a relay, so a break
 * there hides as a skip; these tests need no relay. On iOS the same value
 * encoded twice came out in different key orders, fixed with sorted keys;
 * kotlinx writes keys in declaration order, and these pin that it stays so.
 * In the shared test set: both flavours run it.
 */
class ProjectionBytesTest {

    private val keystore = object : SkiptiKeystore {
        var key: ByteArray? = null
        override fun provision(key: ByteArray) { this.key = key }
        override fun load(): ByteArray? = key
        override fun destroy() { key = null }
    }
    private val path: String =
        Files.createTempDirectory("projection-test").resolve("ledger-vault.db").toString()

    private val me = "0".repeat(31) + "1"
    private val rahul = "0".repeat(31) + "2"
    private val group = "0".repeat(31) + "9"

    private fun seededStore(): LedgerStore {
        val s = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        val base = ExpenseDTO(
            payer = me, paidMinor = 90_000, currencyCode = "INR", group = group, conversion = null,
            shares = listOf(ExpenseDTO.ShareDTO(me, 45_000), ExpenseDTO.ShareDTO(rahul, 45_000)),
            note = "Dinner", timestampMs = 1_000, category = "food.dining", addedBy = me,
        )
        val converted = base.copy(
            paidMinor = 5_000, currencyCode = "USD", note = "Hotel",
            // D-235: an amount-changing conversion breaks core's money rules (shares must add up to the paid amount
            // AND to the converted one: wave A's finding 2; no phone writes a conversion today), and a write now checks
            // them first. A conversion that keeps the amount still carries every conversion field through the bytes.
            conversion = ConversionDTO(5_000, "INR", 1, 1, 2_000),
            shares = listOf(ExpenseDTO.ShareDTO(rahul, 5_000)),
        )
        listOf(base, converted, base).forEachIndexed { i, e -> s.appendExpense(e, nowMs = 5_000L + i) }
        s.appendSettlement(SettlementDTO(rahul, me, 700, "INR", group), nowMs = 6_000)
        return s
    }

    /** The property the round trip proves, without the relay: the ledger
     *  rebuilt from its own records is the same bytes, every time. */
    @Test
    fun rebuilding_from_records_gives_the_same_bytes_every_time() {
        val s = seededStore()
        s.rebuildProjection()
        val expenses = assertNotNullBytes(s.rawKvGet(LedgerStore.EXPENSES_KEY))
        val settlements = assertNotNullBytes(s.rawKvGet(LedgerStore.SETTLEMENTS_KEY))
        repeat(20) { round ->
            s.rebuildProjection()
            assertArrayEquals("expenses, rebuild $round", expenses, s.rawKvGet(LedgerStore.EXPENSES_KEY))
            assertArrayEquals("settlements, rebuild $round", settlements, s.rawKvGet(LedgerStore.SETTLEMENTS_KEY))
        }
    }

    /** The bytes a write leaves are the bytes a rebuild leaves. */
    @Test
    fun an_append_and_a_rebuild_write_the_same_bytes() {
        val s = seededStore()
        val written = assertNotNullBytes(s.rawKvGet(LedgerStore.EXPENSES_KEY))
        s.rebuildProjection()
        assertArrayEquals(written, s.rawKvGet(LedgerStore.EXPENSES_KEY))
    }

    private fun assertNotNullBytes(bytes: ByteArray?): ByteArray {
        assertNotNull(bytes)
        return bytes!!
    }
}
