package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * D-182: Settle up across every group, against the REAL core and vault. The
 * twin of iOS's testSettleUpCoversEveryGroup. The seed is the groups proof's;
 * every number below is worked by hand from it (.drafts/d182/golden.py):
 * a group's entries are what each person owes you after what you owe them.
 */
class SettleAllTest {

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
        val path = Files.createTempDirectory("settle-all").resolve("ledger-vault.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        GroupsProofSeed.seed(store)
        return store
    }

    /** Each group's entries as "name currency minor", by group name. */
    private fun owed(sections: List<SettleSection>): Map<String, Set<String>> =
        sections.associate { s ->
            s.named.group.name to s.lines.flatMap { line ->
                line.entries.map { "${LedgerStore.label(it.id, s.named)} ${line.currencyCode} ${it.minorUnits}" }
            }.toSet()
        }

    @Test
    fun settle_up_covers_every_group_and_a_recorded_payment_settles_one() {
        val store = seeded()
        val before = SettleAll.sections(store)
        assertEquals(setOf("Goa trip", "Flat 4B", "Paris"), before.map { it.named.group.name }.toSet())
        assertEquals(
            mapOf(
                // Villa ₹12,000 you paid, dinner ₹4,800 Rahul, scooters ₹1,600 Meera; 4 ways.
                "Goa trip" to setOf("Rahul INR 180000", "Meera INR 260000", "Sam INR 300000"),
                // Electricity ₹2,700 you paid, groceries ₹1,350 Meera; 3 ways.
                "Flat 4B" to setOf("Meera INR 45000", "Kiran INR 90000"),
                // Museum passes €64 Ana paid; 2 ways: you owe her €32.
                "Paris" to setOf("Ana EUR -3200"),
            ),
            owed(before),
        )
        assertTrue("nothing is settled yet", before.none { it.settled })

        // "I paid Ana": the card's record, through §5.8's path.
        val paris = before.first { it.named.group.name == "Paris" }
        val ana = paris.lines.single().entries.single()
        val settlement = SettleUi.settlementFor(ana, "EUR", paris.named.group.id, paris.named.selfId)
        assertEquals(paris.named.selfId, settlement.from)
        assertEquals(ana.id, settlement.to)
        assertEquals(3_200L, settlement.amountMinor)
        store.appendSettlement(settlement)

        val after = SettleAll.sections(store)
        assertTrue("Paris is all settled", after.first { it.named.group.name == "Paris" }.settled)
        // The other groups are untouched: a settlement is filed under its group.
        assertFalse(after.first { it.named.group.name == "Goa trip" }.settled)
        assertEquals(owed(before) - "Paris", owed(after) - "Paris")
    }

    @Test
    fun settle_up_words_match_ios() {
        assertEquals("Rahul pays you", SettleAllCopy.sentence(youOwe = false, other = "Rahul"))
        assertEquals("You pay Ana", SettleAllCopy.sentence(youOwe = true, other = "Ana"))
        assertEquals("Mark as paid", SettleAllCopy.action(youOwe = false, other = "Rahul"))
        assertEquals("I paid Ana", SettleAllCopy.action(youOwe = true, other = "Ana"))
        assertEquals("Paris is all settled", SettleAllCopy.settled("Paris"))
        assertEquals(
            "Settled with Ana. Recorded in your ledger. No money moved.",
            SettleAllCopy.recorded("Ana"),
        )
        // §11.6, verbatim: the legal sentence rides the screen.
        assertEquals("Marks this as paid here. It does not move money.", SettleAllCopy.EXPLAINER)
    }

    /** D-228: the settle screen says the one recorded sentence through [SettleAllCopy.recorded], as iOS's does; it no
     *  longer builds a second spelling of it from a tail constant. */
    @Test
    fun the_settle_screen_says_the_one_recorded_sentence() {
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/SettleScreen.kt").readText()
        assertFalse("CONFIRMATION_TAIL is gone", "CONFIRMATION_TAIL" in src)
        assertTrue("the screen calls SettleAllCopy.recorded", "SettleAllCopy.recorded(" in src)
        assertFalse("no hand-built sentence", "\"Settled with \"" in src)
    }
}
