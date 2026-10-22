package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiCategoryTreeDefault

/**
 * The expense list's guards, driven against the REAL core and the REAL
 * skrin vault through the host dylib (same JNA bindings the app uses:
 * see D-107/D-108). The vault loop here is the real one: write → close →
 * REOPEN → read persisted bytes, through a test keystore standing in for
 * AndroidKeystore exactly as MemoryKeystore stands in for Keychain in
 * core's own suites.
 */
class LedgerStoreTest {

    /** The platform keystore contract, in memory. 32 bytes, no export path. */
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

    /** One vault on disk; the SAME keystore answers every open, so reopens
     *  unlock (a fresh keystore would provision a different key: WrongKey). */
    private class VaultFixture {
        private val keystore = MemoryKeystore()
        val path: String =
            Files.createTempDirectory("ledger-vault-test").resolve("ledger-vault.db").toString()

        fun store(): LedgerStore = LedgerStore(path) { SkiptiVault.open(path, keystore) }

        /** Writes bytes INTO a key of the real vault, past the store's API:
         *  the corrupt-writer stand-in for the screen's error path. */
        fun rawSet(key: String, bytes: ByteArray) {
            val vault = SkiptiVault.open(path, keystore)
            try {
                vault.kvSet(key, bytes)
            } finally {
                try { vault.closeVault() } catch (_: Exception) {}
            }
        }
    }

    /** The committed cross-shell golden: ONE byte sequence both shells decode. */
    private fun goldenEnvelope(): List<ExpenseDTO> =
        LedgerStore.decodeEnvelope(
            javaClass.getResourceAsStream("/ledger-envelope-golden.json")!!.readBytes(),
        )

    @Test
    fun cross_shell_golden_decodes_the_current_shape() {
        // Same fixture bytes asserted in ios/SkiptiTests/BalanceLineTests.swift
        // (testCrossShellVaultEnvelopeGoldenDecodesIdentically). If the two
        // shells ever disagree, data written on one phone is unreadable on
        // the other, which is why the bytes are committed, not generated.
        val decoded = goldenEnvelope()
        assertEquals(2, decoded.size)
        val full = decoded[0]
        assertEquals(BalanceFixtures.rahul, full.payer)
        assertEquals(5_000L, full.paidMinor)
        assertEquals("USD", full.currencyCode)
        assertEquals(BalanceFixtures.ada, full.group)
        val conversion = checkNotNull(full.conversion) { "conversion must decode" }
        assertEquals(416_250L, conversion.convertedMinor)
        assertEquals("INR", conversion.convertedCurrency)
        assertEquals(8_325L, conversion.rateNum)
        assertEquals(100L, conversion.rateDen)
        assertEquals(1_756_281_600_000L, conversion.rateTimestampMs)
        assertEquals("Rahul paid the USD hotel", full.note)
        assertEquals(1_756_368_000_000L, full.timestampMs)
        assertEquals("food.groceries", full.category)
        assertEquals(listOf(416_250L), full.shares.map { it.amountMinor })
        assertEquals(BalanceFixtures.ada, full.shares[0].id)
    }

    @Test
    fun cross_shell_golden_tolerates_the_older_shape() {
        // The second record was written before note/timestampMs/category
        // existed; the decoder must load it with them ABSENT: the tolerance
        // claim tested, not asserted (Swift's decoder does the same on the
        // same bytes).
        //
        // WHICH MUTATION THIS CATCHES, recorded because an audit pass burned
        // four runs finding out: NOT the Json config. Flipping
        // `explicitNulls`, `ignoreUnknownKeys`, or stripping the property
        // defaults leaves this test green: kotlinx.serialization and Swift's
        // JSONDecoder are both inherently tolerant of an absent key for an
        // OPTIONAL property, which is exactly the cross-shell behaviour being
        // pinned. What it does catch is the realistic regression: making a
        // field REQUIRED (`val note: String`) reddens this test and the
        // current-shape one with it. Probe that way if you are checking
        // whether this guard still bites.
        val old = goldenEnvelope()[1]
        assertEquals(BalanceFixtures.amit, old.payer)
        assertEquals(30_000L, old.paidMinor)
        assertEquals("INR", old.currencyCode)
        assertEquals(BalanceFixtures.ada, old.group)
        assertNull(old.note)
        assertNull(old.timestampMs)
        assertNull(old.category)
        assertNull(old.conversion)
        assertEquals(listOf(30_000L), old.shares.map { it.amountMinor })
    }

    @Test
    fun vault_loop_reads_what_was_persisted_and_does_not_reseed() {
        val fixture = VaultFixture()
        val seeded = fixture.store().loadOrSeed(
            BalanceFixtures.dtoRecords(BalanceFixtures.ada, 1_756_000_000_000L),
            groupID = BalanceFixtures.ada,
        )
        assertEquals(4, seeded.size)
        assertEquals("Dinner in Goa", seeded[0].note)
        assertEquals(BalanceFixtures.ada, seeded[0].group)
        // Second open with an EMPTY seed: no re-seed. The persisted truth
        // comes back unchanged, read from bytes after a close/reopen.
        val again = fixture.store().loadOrSeed(emptyList(), groupID = BalanceFixtures.ada)
        assertEquals(seeded, again)
        // And a plain read agrees.
        assertEquals(seeded, fixture.store().loadExpenses())
    }

    @Test
    fun corrupt_envelope_throws_rather_than_pretending_empty() {
        val fixture = VaultFixture()
        fixture.store().loadOrSeed(
            BalanceFixtures.dtoRecords(BalanceFixtures.ada, 1_756_000_000_000L),
            groupID = BalanceFixtures.ada,
        )
        // A broken writer overwrites the persisted envelope with garbage:
        fixture.rawSet(LedgerStore.EXPENSES_KEY, """[{"payer":"broken""".toByteArray())
        try {
            fixture.store().loadExpenses()
            fail("corrupt vault bytes must surface, never render as an empty ledger")
        } catch (expected: LedgerCorruptException) {
            // exactly the failure the screen renders as "Could not load the ledger"
        }
    }

    @Test
    fun absent_envelope_is_an_empty_ledger_not_an_error() {
        val fixture = VaultFixture()
        assertTrue(fixture.store().loadExpenses().isEmpty())
    }

    @Test
    fun groups_seed_through_core_and_persist() {
        val fixture = VaultFixture()
        val groups = fixture.store().loadGroupsOrSeed()
        assertEquals(1, groups.size)
        assertEquals("Goa Trip", groups[0].name)
        assertEquals("INR", groups[0].settlementCurrency)
        assertEquals(4, groups[0].members.size)
        val again = fixture.store().loadGroupsOrSeed()
        assertEquals(groups, again)
    }

    @Test
    fun toInput_carries_every_value_across_the_boundary() {
        val dto = goldenEnvelope()[0]
        val input = dto.toInput()
        assertEquals(BalanceFixtures.rahul, input.payer)
        assertEquals(5_000L, input.paidMinor)
        assertEquals("USD", input.currencyCode)
        assertEquals(BalanceFixtures.ada, input.group)
        val conversion = checkNotNull(input.conversion) { "conversion must cross" }
        assertEquals(416_250L, conversion.convertedMinor)
        assertEquals("INR", conversion.convertedCurrency)
        assertEquals(8_325L, conversion.rateNum)
        assertEquals(100L, conversion.rateDen)
        assertEquals(1_756_281_600_000L.toULong(), conversion.rateTimestampMs)
        assertEquals(1, input.shares.size)
        assertEquals(BalanceFixtures.ada, input.shares[0].id)
        assertEquals(416_250L, input.shares[0].amountMinor)
    }

    @Test
    fun list_sorts_newest_first_and_sinks_untimestamped_rows() {
        val a = ExpenseDTO(
            payer = BalanceFixtures.ada, paidMinor = 1, currencyCode = "INR",
            timestampMs = 3_000L, note = "old",
        )
        val b = ExpenseDTO(
            payer = BalanceFixtures.ada, paidMinor = 2, currencyCode = "INR",
            timestampMs = 9_000L, note = "newest",
        )
        val c = ExpenseDTO(
            payer = BalanceFixtures.ada, paidMinor = 3, currencyCode = "INR",
            timestampMs = 3_000L, note = "old tie zebra",
        )
        val noTs = ExpenseDTO(
            payer = BalanceFixtures.ada, paidMinor = 4, currencyCode = "INR",
            note = "no timestamp",
        )
        assertEquals(
            listOf(b, c, a, noTs).map { it.paidMinor },
            ExpenseListUi.sortedNewestFirst(listOf(a, b, c, noTs)).map { it.paidMinor },
        )
    }

    @Test
    fun row_label_combines_note_payer_and_spoken_amount() {
        assertEquals(
            "Dinner in Goa. Paid by You. two thousand one hundred rupees.",
            ExpenseListUi.rowLabel(
                "Dinner in Goa", "You",
                BalanceUi.moneySpoken(210_000, "INR", LocaleTag.EN_IN),
            ),
        )
        assertEquals(
            "Expense. Paid by Rahul. minus one thousand two hundred and forty rupees.",
            ExpenseListUi.rowLabel(
                null, "Rahul", "minus one thousand two hundred and forty rupees",
            ),
        )
    }

    @Test
    fun category_label_resolves_leaf_through_parent_and_blanks_unknown() {
        val tree = ffiCategoryTreeDefault()
        // A real leaf resolves "Parent · Leaf" (D-174: no longer uppercased);
        // an unknown id and a parent id
        // itself show NOTHING, never a guess (§8.2's rule at label scale).
        val leaf = tree.categories.first { it.parent != null }
        val parentName = tree.categories.first { it.id == leaf.parent }.name
        assertEquals(
            "${parentName} · ${leaf.name}",
            ExpenseListUi.categoryLabel(tree, leaf.id),
        )
        assertNull(ExpenseListUi.categoryLabel(tree, "no-such-category-id"))
        assertNull(ExpenseListUi.categoryLabel(tree, leaf.parent))
        assertNull(ExpenseListUi.categoryLabel(tree, null))
    }

    /** D-221 fix wave (S10): dates come from the locale's own skeleton `dMMMy`, as iOS's `AppDate.day`
     *  (`setLocalizedDateFormatFromTemplate`): en-US reads "Aug 24, 2025" and en-IN "24 Aug 2025", never one fixed
     *  pattern. The JVM has no ICU, so the test hands AppDate the JDK's CLDR patterns (JvmDates); the app uses the
     *  platform's (android.text.format.DateFormat). Presentation, not money (D-54). */
    @Test
    fun dates_format_from_the_locales_skeleton() {
        JvmDates.install()
        val ms = 1_756_000_000_000 // 2025-08-24T01:46:40Z
        val utc = java.time.ZoneOffset.UTC
        assertEquals("Aug 24, 2025", AppDate.day(ms, LocaleTag.EN_US, utc))
        assertEquals("24 Aug 2025", AppDate.day(ms, LocaleTag.EN_IN, utc))
        assertEquals(AppDate.day(ms, LocaleTag.EN_US), ExpenseListUi.dateString(ms, LocaleTag.EN_US))
        assertEquals(AppDate.day(ms, LocaleTag.EN_IN), ExpenseListUi.dateString(ms, LocaleTag.EN_IN))
        assertEquals("the import review's dates, in UTC", AppDate.day(ms, LocaleTag.EN_IN, utc), ImportReview.dateString(ms.toULong(), LocaleTag.EN_IN))
        assertTrue(AppDate.stamp(ms, hours24 = false, tag = LocaleTag.EN_US).startsWith("Aug 2"))
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/AppLocale.kt").readText()
        assertTrue("the platform's skeleton", "android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)" in src)
        // D-225 T7 (A4): the stamp is no longer a skeleton but iOS's resolved patterns, written out (the test below).
        assertTrue("iOS's day template", "\"dMMMy\"" in src)
        val sources = AppSources.listFiles { f -> f.name.endsWith(".kt") }!!
        for (f in sources) assertFalse("${f.name}: a fixed date pattern", Regex("ofPattern\\(\"d MMM").containsMatchIn(f.readText()) || "FormatStyle.MEDIUM" in f.readText())
    }

    /** D-225 T7 (A4, from D-223's look: iOS "Oct 17, 2026 at 6:01 AM", Android "Oct 17, 2026, 5:53 AM"): the expense
     *  detail's "Added by" and "Last edited by" stamp is ONE wording on both phones, on the app's en-US / en-IN axis.
     *  The goldens were read off iOS's own formatter (Foundation's DateFormatter, template "dMMMyjmm", en_US and en_IN,
     *  2026-10-17 on this Mac), not out of this implementation: "at" between date and time, a narrow no-break space
     *  (U+202F) before the marker, "AM" / "PM" and English "Sep" on both tags. iOS's twin pins the same strings.
     *  D-225 fix wave (I2, A10, WORKLOG decision 25): the time follows the phone's 12 or 24-hour setting; the 24-hour
     *  column is task-6-report's golden table, iOS's `testTheAddedByStampIsOneWordingOnBothPhones`, word for word. */
    @Test
    fun the_added_by_stamp_is_one_wording_with_ios() {
        val ms = 1_792_216_860_000 // 2026-10-17T06:01:00Z
        val utc = java.time.ZoneOffset.UTC
        val kolkata = java.time.ZoneId.of("Asia/Kolkata")
        val nnbsp = " "
        val september = 1_789_516_800_000 // 2026-09-16T00:00:00Z
        // (instant, tag, zone, 12-hour, 24-hour): iOS's table.
        val table = listOf(
            listOf(ms, LocaleTag.EN_US, utc, "Oct 17, 2026 at 6:01${nnbsp}AM", "Oct 17, 2026 at 06:01"),
            listOf(ms, LocaleTag.EN_IN, utc, "17 Oct 2026 at 6:01${nnbsp}AM", "17 Oct 2026 at 06:01"),
            listOf(ms, LocaleTag.EN_US, kolkata, "Oct 17, 2026 at 11:31${nnbsp}AM", "Oct 17, 2026 at 11:31"),
            listOf(ms, LocaleTag.EN_IN, kolkata, "17 Oct 2026 at 11:31${nnbsp}AM", "17 Oct 2026 at 11:31"),
            listOf(ms + 8 * 3_600_000, LocaleTag.EN_US, utc, "Oct 17, 2026 at 2:01${nnbsp}PM", "Oct 17, 2026 at 14:01"),
            listOf(september, LocaleTag.EN_IN, utc, "16 Sep 2026 at 12:00${nnbsp}AM", "16 Sep 2026 at 00:00"),
            listOf(september, LocaleTag.EN_US, kolkata, "Sep 16, 2026 at 5:30${nnbsp}AM", "Sep 16, 2026 at 05:30"),
        )
        for (row in table) {
            val at = row[0] as Long
            val tag = row[1] as LocaleTag
            val zone = row[2] as java.time.ZoneId
            assertEquals("12-hour $tag $zone", row[3], AppDate.stamp(at, hours24 = false, tag = tag, zone = zone))
            assertEquals("24-hour $tag $zone", row[4], AppDate.stamp(at, hours24 = true, tag = tag, zone = zone))
        }
        // Whatever ICU the phone has: the stamp reads no platform skeleton.
        val saved = AppDate.bestPattern
        AppDate.bestPattern = { _, _ -> "'wrong'" }
        try {
            assertEquals("Oct 17, 2026 at 6:01${nnbsp}AM", AppDate.stamp(ms, hours24 = false, tag = LocaleTag.EN_US, zone = utc))
            assertEquals("Oct 17, 2026 at 06:01", AppDate.stamp(ms, hours24 = true, tag = LocaleTag.EN_US, zone = utc))
        } finally {
            AppDate.bestPattern = saved
        }
        for (h24 in listOf(false, true)) {
            assertEquals("the detail screen's stamp is AppDate's", AppDate.stamp(ms, h24, LocaleTag.EN_IN), ExpenseDetailUi.stamp(ms, LocaleTag.EN_IN, h24))
        }
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/AppLocale.kt").readText()
        // A4, A10: the Gregorian calendar, pinned, whatever the formatter's default.
        assertTrue("the ISO (Gregorian) chronology, pinned", ".withChronology(IsoChronology.INSTANCE)" in src)
        // The detail screen asks the phone for its 12 or 24-hour setting.
        val detail = java.io.File("src/main/java/com/vardrlabs/skipti/ExpenseDetailScreen.kt").readText()
        assertTrue("the phone's setting", "android.text.format.DateFormat.is24HourFormat(context)" in detail)
        assertEquals("both stamps follow it", 2, detail.split("AppLocale.current, hours24)").size - 1)
    }

    /** D-225 fix wave (I2, A10): the four stamp patterns are iOS's, read out of AppLocale.swift. */
    @Test
    fun the_stamp_patterns_are_ios_patterns() {
        val swift = java.io.File("../../ios/Skipti/App/AppLocale.swift").readText()
        fun ios(name: String) = Regex("""static let $name = "([^"]*)"""").find(swift)!!.groupValues[1].replace("\\u{202F}", " ")
        assertEquals(ios("stampUS"), AppDate.STAMP_US)
        assertEquals(ios("stampIN"), AppDate.STAMP_IN)
        assertEquals(ios("stampUS24"), AppDate.STAMP_US24)
        assertEquals(ios("stampIN24"), AppDate.STAMP_IN24)
        assertEquals(AppDate.STAMP_US, AppDate.stampPattern(LocaleTag.EN_US, hours24 = false))
        assertEquals(AppDate.STAMP_US24, AppDate.stampPattern(LocaleTag.EN_US, hours24 = true))
        assertEquals(AppDate.STAMP_IN, AppDate.stampPattern(LocaleTag.EN_IN, hours24 = false))
        assertEquals(AppDate.STAMP_IN24, AppDate.stampPattern(LocaleTag.EN_IN, hours24 = true))
    }

    /** D-162 (SPEC §2.3 A7, §3.7): nothing goes into a Google backup or a
     *  device transfer. Read from the source the build packages. */
    @Test
    fun the_vault_is_never_backed_up() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("allowBackup off", manifest.contains("android:allowBackup=\"false\""))
        assertTrue("no full backup either", manifest.contains("android:fullBackupContent=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = doc.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            assertEquals("nothing included in $section", 0, node.getElementsByTagName("include").length)
            val excluded = (0 until node.getElementsByTagName("exclude").length).map {
                val e = node.getElementsByTagName("exclude").item(it) as org.w3c.dom.Element
                e.getAttribute("domain") to e.getAttribute("path")
            }.toSet()
            assertEquals(
                "every domain excluded from $section",
                listOf("root", "file", "database", "sharedpref", "external").map { it to "." }.toSet(),
                excluded,
            )
        }
    }
}
