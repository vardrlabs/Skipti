package com.vardrlabs.skipti

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.PortException
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiApplyImportPlan

/** D-166 (SPEC §9.5): the import flow's steps against the real core and a
 *  throwaway vault; the twin of iOS's ImportFlowTests. */
class ImportFlowTest {
    private val splitwise = ImportProof.splitwiseSheet()
    private val wallet = ImportProof.budgetBakersSheet()

    private fun tempStore(): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = Files.createTempDirectory("import-test").resolve("v.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    @Test
    fun the_file_is_read_as_text_without_its_mark_and_within_a_limit() {
        assertEquals("a,b\r\n", ImportSetup.text("﻿a,b\r\n".toByteArray()))
        assertEquals("a,b", ImportSetup.text("a,b".toByteArray()))
        try {
            ImportSetup.text(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())); fail()
        } catch (e: ImportSetup.ReadException) { assertFalse(e.tooLarge) }
        try {
            ImportSetup.text(ByteArray(ImportSetup.MAX_BYTES + 1)); fail()
        } catch (e: ImportSetup.ReadException) { assertTrue(e.tooLarge) }
        // A capped read stops one byte past the limit.
        val big = ByteArray(ImportSetup.MAX_BYTES + 5000)
        assertEquals(ImportSetup.MAX_BYTES + 1, ImportSetup.readCapped(big.inputStream()).size)
    }

    @Test
    fun the_sheets_people_come_from_cores_reading_of_the_header() {
        assertEquals(listOf("Alice", "Bob"), ImportSetup.people(splitwise))
        try {
            ImportSetup.people(wallet); fail("a wallet statement is not a Splitwise group sheet")
        } catch (_: PortException.SheetStructure) {}
    }

    @Test
    fun the_guesses_are_only_guesses() {
        assertEquals("Bob", ImportSetup.guessYou(listOf("Alice", "Bob"), "bob"))
        assertEquals("Alice", ImportSetup.guessYou(listOf("Alice", "Bob"), " ALICE "))
        assertNull(ImportSetup.guessYou(listOf("Alice", "Bob"), null))
        assertNull(ImportSetup.guessYou(listOf("Alice", "Bob"), "Carol"))

        assertEquals("Goa trip", ImportSetup.groupName("goa_trip-2026.csv", ImportSource.SPLITWISE))
        assertEquals("Tahoe trip", ImportSetup.groupName("tahoe_trip_2026-09-21_export.csv", ImportSource.SPLITWISE))
        assertEquals("Splitwise import", ImportSetup.groupName("___.csv", ImportSource.SPLITWISE))
        assertEquals("Wallet", ImportSetup.groupName("-.csv", ImportSource.BUDGET_BAKERS))
        assertEquals("Wallet", ImportSetup.groupName("Export 2.csv", ImportSource.BUDGET_BAKERS))

        assertEquals("USD", ImportSetup.mostCommon(listOf("INR", "USD", "USD")))
        assertEquals("a tie goes to the first seen", "EUR", ImportSetup.mostCommon(listOf("EUR", "USD")))
        assertNull(ImportSetup.mostCommon(emptyList()))
        // The sheet's two accepted rows are USD dinners (its INR row is refused).
        assertEquals("USD", ImportSetup.likelyCurrency(splitwise, ImportSource.SPLITWISE, listOf("Alice", "Bob"), "CHF"))
        assertEquals("CHF", ImportSetup.likelyCurrency("not,a\n1,2\n", ImportSource.SPLITWISE, emptyList(), "CHF"))
        assertEquals("INR", ImportSetup.likelyCurrency(wallet, ImportSource.BUDGET_BAKERS, emptyList(), "CHF"))
    }

    /** Goldens computed in Python from the calendar, not from the code. */
    @Test
    fun a_files_date_keeps_its_calendar_day_on_this_phone() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        val midnight = 1_789_862_400_000L // 2026-09-20T00:00Z, a Splitwise date
        assertEquals("noon, 20 Sep, New York", 1_789_920_000_000L, ImportSetup.localWallTime(midnight, ny))
        assertEquals("noon, 20 Sep, Kolkata", 1_789_885_800_000L, ImportSetup.localWallTime(midnight, kolkata))
        val wall = 1_768_483_800_000L // 2026-01-15 13:30, a wallet row
        assertEquals("13:30 in New York (EST)", 1_768_501_800_000L, ImportSetup.localWallTime(wall, ny))
        assertEquals(wall, ImportSetup.localWallTime(wall, TimeZone.getTimeZone("UTC")))
        // The review shows the file's own date wherever the phone is.
        JvmDates.install()
        assertTrue(ImportReview.dateString(midnight.toULong(), LocaleTag.EN_US).contains("20"))
    }

    @Test
    fun a_refusal_names_people_not_ids() {
        val a = "a".repeat(32)
        val b = "b".repeat(32)
        assertEquals(
            "2 payers recorded (Tim, Asha)",
            ImportReview.naming("2 payers recorded ($a, $b)", mapOf(a to "Tim", b to "Asha")),
        )
        assertEquals("blank cost", ImportReview.naming("blank cost", mapOf(a to "Tim")))
    }

    @Test
    fun a_splitwise_import_writes_nothing_until_commit_then_all_of_it() {
        val store = tempStore()
        val job = ImportJob.prepare(store, splitwise, ImportSource.SPLITWISE, "Tahoe", "USD", listOf("Alice", "Bob"), "Alice", "")
        // Planned, checked by core, and still nothing saved.
        assertEquals(2, job.plan.expenses.size)
        // The plan was checked against the ledger plus the unsaved group. (Core
        // checks only a group id's shape today: BUILD-LOG §4, D-166.)
        assertTrue(job.existing.groups.any { it.id == job.group?.group?.id })
        ffiApplyImportPlan(job.existing, job.plan)
        assertTrue(store.loadGroups().isEmpty())
        assertTrue(store.loadExpenses().isEmpty())
        assertEquals("the chosen column becomes your name when you have none", "Alice", job.newName)

        val before = store.allRecords().size
        job.commit(job.plan.expenses, store)

        val groups = store.namedGroups()
        assertEquals(listOf("Tahoe"), groups.map { it.group.name })
        val group = groups.first()
        assertEquals(setOf("Alice", "Bob"), group.members.map { it.name }.toSet())
        assertEquals("Alice", store.ensureSelf().name)
        assertEquals(group.group.id, store.currentGroup()?.group?.id)
        val expenses = store.loadExpenses()
        assertEquals(2, expenses.size)
        // Saved in this phone's time zone on the file's calendar day.
        val zone = ZoneId.systemDefault()
        assertEquals(
            job.plan.expenses.mapNotNull { it.timestampMs }.map { Instant.ofEpochMilli(it.toLong()).atZone(ZoneOffset.UTC).toLocalDate() },
            expenses.mapNotNull { it.timestampMs }.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
        )
        for (expense in expenses) {
            assertEquals(group.group.id, expense.group)
            assertTrue(expense.payer in group.group.members)
            assertEquals(group.selfId, expense.addedBy)
        }
        // Each expense is also its own sync record. Counted by type (1, straumr's
        // expense): the group and your name write records too, and a rebuild's
        // one-time legacy backfill would re-mint missing ones (sabotage run 1).
        assertEquals(2, store.allRecords().count { it.recordType.toInt() == 1 })
        assertTrue(store.allRecords().size > before)
        store.rebuildProjection()
        assertEquals(2, store.loadExpenses().size)
    }

    @Test
    fun a_wallet_import_is_yours_alone_and_needs_a_name() {
        val store = tempStore()
        try {
            ImportJob.prepare(store, wallet, ImportSource.BUDGET_BAKERS, "Wallet", "INR", emptyList(), null, "  ")
            fail("no name")
        } catch (_: NeedsYourNameException) {}
        val job = ImportJob.prepare(store, wallet, ImportSource.BUDGET_BAKERS, "Wallet", "INR", emptyList(), null, "Tim")
        assertEquals("the one expense row; income and garbage refused", listOf(254_750L), job.plan.expenses.map { it.paidMinor })
        job.commit(job.plan.expenses, store)
        val group = store.namedGroups().first()
        assertEquals(listOf("Tim"), group.members.map { it.name })
        assertEquals(listOf(group.selfId), store.loadExpenses().map { it.payer })
    }
}
