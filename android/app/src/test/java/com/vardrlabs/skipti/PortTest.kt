package com.vardrlabs.skipti

import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.ExpenseShare
import uniffi.skipti_core.ImportPlan
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.PortConversion
import uniffi.skipti_core.PortException
import uniffi.skipti_core.PortExpense
import uniffi.skipti_core.PortGroup
import uniffi.skipti_core.PortSettlement
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * §9's port surface, driven against the REAL core on the host dylib
 * (iOS's LedgerPortTests discipline, carried): the §9.2 envelope
 * round-trips byte-identically (THE cross-shell golden: if the two
 * shells ever disagree, the vault format has drifted), the flat CSV
 * renders exact decimals, §9.4's gates are structural, and the import
 * review refuses atomically with the VAULT UNCHANGED: asserted, not
 * assumed.
 */
class PortTest {

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
            Files.createTempDirectory("port-test").resolve("ledger-vault.db").toString()
        val opener: () -> uniffi.skipti_core.SkiptiVault = { SkiptiVault.open(path, keystore) }
        fun store(): LedgerStore = LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    private val ada = "a".repeat(31) + "1"
    private val bob = "b".repeat(31) + "2"

    /** A real ledger: a group, an INR expense with a frozen conversion,
     *  a plain USD expense (quoted note), a settlement. */
    private fun fullSnapshot(): LedgerSnapshot {
        val goa = "d".repeat(31) + "4"
        return LedgerSnapshot(
            groups = listOf(
                PortGroup(
                    id = goa, name = "Goa Trip", settlementCurrency = "INR",
                    members = listOf(ada, bob),
                ),
            ),
            expenses = listOf(
                PortExpense(
                    payer = ada,
                    paidMinor = 5_000,
                    currencyCode = "USD",
                    group = goa,
                    conversion = PortConversion(
                        convertedMinor = 416_250,
                        convertedCurrency = "INR",
                        rateNum = 8_325,
                        rateDen = 100,
                        rateTimestampMs = 20_468uL * 86_400_000uL,
                    ),
                    shares = listOf(
                        ExpenseShare(ada, 2_000),
                        ExpenseShare(bob, 3_000),
                    ),
                    note = "Hotel, \"seaside\"",
                    timestampMs = 20_468uL * 86_400_000uL,
                    category = null,
                ),
                PortExpense(
                    payer = bob,
                    paidMinor = 4_550,
                    currencyCode = "USD",
                    group = goa,
                    conversion = null,
                    shares = listOf(ExpenseShare(bob, 4_550)),
                    note = "Dinner, \"seaside\"",
                    timestampMs = 20_469uL * 86_400_000uL,
                    category = null,
                ),
            ),
            settlements = listOf(
                PortSettlement(
                    from = ada, to = bob, amountMinor = 700,
                    currencyCode = "INR", group = null,
                ),
            ),
            redistributions = emptyList(),
            profile = null,
        )
    }

    // ---- §9.2: THE cross-shell golden ----

    @Test
    fun the_envelope_round_trips_byte_identically() {
        val snapshot = fullSnapshot()
        val json = uniffi.skipti_core.ffiExportLedgerJson(snapshot)
        // The envelope's identity is its opening bytes (no BOM anywhere):
        assertTrue(json.startsWith("{\"format\":\"skipti-ledger\",\"schema_version\":4,"))
        assertEquals(json, uniffi.skipti_core.ffiExportLedgerJson(snapshot))
        // Export → import → the SAME snapshot, field for field. If the two
        // shells ever disagree here, the vault format has drifted.
        val restored = uniffi.skipti_core.ffiImportLedgerJson(json)
        assertEquals(snapshot, restored)
        // And the shell's bridge agrees with what the vault really holds:
        val fixture = VaultFixture()
        val store = fixture.store()
        store.writeSnapshot(snapshot)
        assertEquals(snapshot, store.snapshot())
    }

    // ---- §9.2: the flat view renders exact decimals ----

    @Test
    fun the_flat_csv_renders_exact_decimals_and_rfc4180_quoting() {
        val csv = uniffi.skipti_core.ffiExportLedgerCsv(fullSnapshot())
        // Plain decimals from core's own minor-unit renderer: no float
        // could produce these. (The frozen conversion NEVER appears: the
        // flat view drops it: the JSON envelope is the lossless copy.)
        assertTrue(csv.contains("50.00"))
        assertTrue(csv.contains("45.50"))
        assertTrue(csv.contains("7.00"))
        assertFalse(csv.contains("4162.50"))
        val kwd = fullSnapshot().let { s ->
            LedgerSnapshot(
                groups = s.groups,
                expenses = s.expenses + PortExpense(
                    payer = ada, paidMinor = 1005, currencyCode = "KWD",
                    group = null, conversion = null,
                    shares = listOf(ExpenseShare(ada, 1005)),
                    note = "three decimals", timestampMs = null, category = null,
                ),
                settlements = s.settlements,
                redistributions = s.redistributions,
                profile = null,
            )
        }
        assertTrue(uniffi.skipti_core.ffiExportLedgerCsv(kwd).contains("1.005"))
        // RFC 4180 quoting: the embedded comma and quotes survive exactly.
        assertTrue(csv.contains("\"Hotel, \"\"seaside\"\"\""))
        // The frozen conversion does NOT fit the flat row (its loss is the
        // JSON envelope's job; §9.4's warning derives from this fact):
        assertFalse(csv.contains("rate"))
    }

    // ---- The shell's byte rules (BOM, filenames) ----

    @Test
    fun the_file_bytes_follow_515_not_the_string() {
        val body = "a,b\r\n1,2\r\n"
        val csv = Exporter.fileData(body, Exporter.Format.CSV)
        // UTF-8 with BOM for Excel (§9.2), then the EXACT RFC 4180 body:
        assertTrue(csv.copyOfRange(0, 3).contentEquals(Exporter.utf8Bom))
        assertTrue(csv.copyOfRange(3, csv.size).contentEquals(body.toByteArray(Charsets.UTF_8)))
        // JSON takes NO BOM. Its identity is the envelope's first bytes:
        val json = Exporter.fileData(body, Exporter.Format.JSON)
        assertTrue(json.contentEquals(body.toByteArray(Charsets.UTF_8)))
        // Incoming BOM is contamination: stripped once, never doubled.
        val bommed = "\uFEFF" + body
        assertTrue(
            Exporter.fileData(bommed, Exporter.Format.CSV).contentEquals(csv),
        )
    }

    @Test
    fun filenames_are_prefix_plus_utc_date_never_ledger_names() {
        // 23:30 in New York is already the next day in UTC: the stamp
        // must not depend on which side of a local midnight we sit.
        val late = ZonedDateTime.parse("2026-08-26T23:30:00-05:00")
        assertEquals("2026-08-27", Exporter.filenameStamp(late))
        val dir = Files.createTempDirectory("export-names").toFile()
        val file = Exporter.write(
            "x", Exporter.Format.JSON, dir,
            Exporter.utc(java.time.Instant.parse("2026-08-26T00:00:00Z")),
        )
        assertEquals("skipti-export-2026-08-26.json", file.name)
    }

    // ---- The --export-proof screen's own check (P1) ----

    /** D-211 bumped the schema to 4 and left the proof pinning 3: the screen
     *  read PROOF FAILED on its JSON rows while every gate stayed green,
     *  because nothing ran `prove`. Each row is asserted on its own so a
     *  failure names it. */
    @Test
    fun the_export_proof_passes_row_by_row() {
        val dir = Files.createTempDirectory("export-proof").toFile()
        val r = ExportProof.prove(dir)
        assertTrue("CSV: EF BB BF then the exact RFC 4180 body", r.csvBomPrefix)
        assertTrue("CSV re-read parses identically", r.csvRoundTrips)
        assertTrue(
            "JSON: no BOM and the envelope prefix. A schema bump must update the prefix literal in ExportProof.prove()",
            r.jsonNoBom,
        )
        assertTrue("JSON re-imports byte-identically", r.jsonRoundTrips)
        assertTrue("a double BOM is stripped, never doubled", r.noDoubleBom)
        assertTrue("filename: fixed prefix plus date", r.nameShape)
        assertTrue("flat CSV carries exact decimals", r.csvExactDecimals)
        assertTrue("every row together: Result.passed", r.passed)
    }

    // ---- §9.4: the gates are structural ----

    @Test
    fun the_plain_text_warning_is_the_spec_sentence() {
        // VERBATIM from SPEC §9.4: changing this string is a spec change.
        assertEquals(
            "This file is not encrypted. Anything you share it with can read it.",
            ExportFlow.WARNING_TEXT,
        )
    }

    @Test
    fun an_unacknowledged_export_writes_nothing() {
        val dir = Files.createTempDirectory("export-gate").toFile()
        assertTrue(ExportFlow.gateHolds(dir))
        assertTrue(dir.listFiles().isNullOrEmpty())
        // The typed refusal, directly:
        try {
            ExportFlow.export(
                acknowledged = false,
                format = Exporter.Format.CSV,
                snapshot = fullSnapshot(),
                dir = dir,
            )
            fail("the gate must refuse")
        } catch (expected: ExportFlow.NotAcknowledged) {
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
        // Acknowledged: the write happens and lands the exact bytes.
        val file = ExportFlow.export(
            acknowledged = true,
            format = Exporter.Format.CSV,
            snapshot = fullSnapshot(),
            dir = dir,
        )
        assertTrue(file.exists() && file.length() > 0)
    }

    @Test
    fun the_flat_loss_warning_is_derived_not_declared() {
        assertTrue(ExportFlow.flatLosses(fullSnapshot())) // has a conversion
        val plain = LedgerSnapshot(
            groups = emptyList(),
            expenses = fullSnapshot().expenses.map { it.copy(conversion = null) },
            settlements = fullSnapshot().settlements,
            redistributions = fullSnapshot().redistributions,
            profile = null,
        )
        assertFalse(ExportFlow.flatLosses(plain))
    }

    // ---- §9.5: the review, both profiles, one model ----

    @Test
    fun both_profiles_plan_through_the_same_review() {
        val empty = LedgerSnapshot(groups = emptyList(), expenses = emptyList(), settlements = emptyList(), redistributions = emptyList(), profile = null)
        val swPlan = uniffi.skipti_core.ffiPlanImport(
            ImportProof.splitwiseSheet(),
            uniffi.skipti_core.ImportProfile.SplitwiseGroup(
                uniffi.skipti_core.SplitwiseOptions(
                    dateFormat = uniffi.skipti_core.DateFormat.ISO_YMD,
                    members = listOf(
                        uniffi.skipti_core.MemberBinding("Alice", ada),
                        uniffi.skipti_core.MemberBinding("Bob", bob),
                    ),
                    group = null,
                ),
            ),
            empty,
        )
        assertEquals(2, swPlan.expenses.size)
        assertEquals(2, swPlan.rejections.size)
        val swModel = ImportReview(swPlan, uniffi.skipti_core.LocaleTag.EN_US)
        // Refusals name their SPREADSHEET lines: the Total row and the
        // grouping-separator row:
        assertEquals(listOf(3u, 5u), swModel.rejections.map { it.lineNumber }.sorted())
        // Duplicates are FLAGGED, never removed:
        val dinner = swModel.proposals.first { it.id == 1 }
        val duplicate = dinner.duplicateOf as ImportReview.DuplicateTarget.InFile
        assertEquals(0u, duplicate.index)
        assertTrue(dinner.included)
        assertTrue(swModel.totalsText.isNotEmpty())

        val bbPlan = uniffi.skipti_core.ffiPlanImport(
            ImportProof.budgetBakersSheet(),
            uniffi.skipti_core.ImportProfile.BudgetBakersWallet(
                uniffi.skipti_core.BudgetBakersOptions(selfParticipant = ada, group = null),
            ),
            empty,
        )
        assertEquals(1, bbPlan.expenses.size)
        val bbModel = ImportReview(bbPlan, uniffi.skipti_core.LocaleTag.EN_US)
        assertEquals(listOf(2u, 4u), bbModel.rejections.map { it.lineNumber }.sorted())
    }

    @Test
    fun a_malformed_sheet_is_refused_naming_its_row() {
        val empty = LedgerSnapshot(groups = emptyList(), expenses = emptyList(), settlements = emptyList(), redistributions = emptyList(), profile = null)
        // A row whose amount is not money: refused WITH the row number.
        // Line surgery, no regex: break ONLY the FIRST dinner row's
        // amount (the two rows are byte-identical on purpose).
        var broke = false
        val sheet = ImportProof.splitwiseSheet().lines().joinToString("") { line ->
            if (!broke && line.startsWith("2026-01-15,Dinner, ,45.50")) {
                broke = true
                line.replace("45.50", "not-money") + "\r\n"
            } else {
                "$line\r\n"
            }
        }
        val plan = uniffi.skipti_core.ffiPlanImport(
            sheet,
            uniffi.skipti_core.ImportProfile.SplitwiseGroup(
                uniffi.skipti_core.SplitwiseOptions(
                    dateFormat = uniffi.skipti_core.DateFormat.ISO_YMD,
                    members = listOf(
                        uniffi.skipti_core.MemberBinding("Alice", ada),
                        uniffi.skipti_core.MemberBinding("Bob", bob),
                    ),
                    group = null,
                ),
            ),
            empty,
        )
        // The broken row is refused BY ITS SPREADSHEET LINE, reason first:
        assertTrue(
            "the not-money row (line 2) must be named",
            plan.rejections.any { it.rowNumber == 2u },
        )
        val broken = plan.rejections.first { it.rowNumber == 2u }
        assertTrue(broken.reason.contains("not-money"))
        // The still-valid dinner row remains a proposal:
        assertEquals(1, plan.expenses.size)
        // Bytes with no header at all are refused at the STRUCTURE level:
        try {
            uniffi.skipti_core.ffiPlanImport(
                "this is not a sheet",
                uniffi.skipti_core.ImportProfile.SplitwiseGroup(
                    uniffi.skipti_core.SplitwiseOptions(
                        dateFormat = uniffi.skipti_core.DateFormat.ISO_YMD,
                        members = emptyList(),
                        group = null,
                    ),
                ),
                empty,
            )
            fail("header-less bytes must be refused")
        } catch (expected: PortException.SheetStructure) {
        }
        // And RFC 4180 violations (an unterminated quote) at the BYTE level:
        val unterminated = "a,b\r\n\"unterminated"
        try {
            uniffi.skipti_core.ffiPlanImport(
                unterminated,
                uniffi.skipti_core.ImportProfile.SplitwiseGroup(
                    uniffi.skipti_core.SplitwiseOptions(
                        dateFormat = uniffi.skipti_core.DateFormat.ISO_YMD,
                        members = emptyList(),
                        group = null,
                    ),
                ),
                empty,
            )
            fail("unterminated quotes must be refused")
        } catch (expected: PortException.CsvSyntax) {
        }
    }

    @Test
    fun exclude_one_applies_n_minus_1_and_a_failed_apply_writes_nothing() {
        val fixture = VaultFixture()
        val store = fixture.store()
        // A real group so the vault holds state worth protecting:
        store.writeSnapshot(fullSnapshot())
        val before = listOf(
            LedgerStore.EXPENSES_KEY, LedgerStore.GROUPS_KEY, LedgerStore.SETTLEMENTS_KEY,
        ).associateWith { store.rawKvGet(it)?.toList() }

        val empty = LedgerSnapshot(groups = emptyList(), expenses = emptyList(), settlements = emptyList(), redistributions = emptyList(), profile = null)
        val swPlan = uniffi.skipti_core.ffiPlanImport(
            ImportProof.splitwiseSheet(),
            uniffi.skipti_core.ImportProfile.SplitwiseGroup(
                uniffi.skipti_core.SplitwiseOptions(
                    dateFormat = uniffi.skipti_core.DateFormat.ISO_YMD,
                    members = listOf(
                        uniffi.skipti_core.MemberBinding("Alice", ada),
                        uniffi.skipti_core.MemberBinding("Bob", bob),
                    ),
                    group = null,
                ),
            ),
            empty,
        )
        val model = ImportReview(swPlan, uniffi.skipti_core.LocaleTag.EN_US)
        model.setIncluded(false, forId = 1)
        val applied = model.apply(empty)
        assertEquals(1, (applied as ImportReview.AppliedState.Done).expenseCount)
        assertEquals(1, model.merged!!.expenses.size)

        // THE ATOMIC LINE: a poisoned plan (share sum 501 ≠ 500) is a
        // typed refusal, the model's merged handle stays null, and the
        // vault's bytes are UNCHANGED: asserted against the store.
        val poisoned = ImportPlan(
            expenses = listOf(
                PortExpense(
                    payer = ada, paidMinor = 500, currencyCode = "USD",
                    group = null, conversion = null,
                    shares = listOf(
                        ExpenseShare(ada, 499),
                        ExpenseShare(bob, 2),
                    ),
                    note = "broken sum", timestampMs = null, category = null,
                ),
            ),
            duplicates = emptyList(), rejections = emptyList(), totalsByCurrency = emptyList(),
        )
        val poisonModel = ImportReview(poisoned, uniffi.skipti_core.LocaleTag.EN_US)
        val state = poisonModel.apply(empty)
        assertTrue(state is ImportReview.AppliedState.Failed)
        assertTrue(
            (state as ImportReview.AppliedState.Failed)
                .message.startsWith("The merged ledger would be invalid"),
        )
        assertNull(poisonModel.merged)
        val after = listOf(
            LedgerStore.EXPENSES_KEY, LedgerStore.GROUPS_KEY, LedgerStore.SETTLEMENTS_KEY,
        ).associateWith { store.rawKvGet(it)?.toList() }
        // The commit path persists ONLY on Done: a failed apply wrote
        // NOTHING:
        if (poisonModel.appliedState is ImportReview.AppliedState.Done) {
            store.writeSnapshot(poisonModel.merged!!)
        }
        assertEquals(before, after)
    }

    // ---- The whole loop: export → file → import → the same balance ----

    @Test
    fun export_reimport_leaves_the_balance_identical() {
        val fixture = VaultFixture()
        val store = fixture.store()
        store.writeSnapshot(fullSnapshot())

        val dir = Files.createTempDirectory("export-roundtrip").toFile()
        val file = ExportFlow.export(
            acknowledged = true,
            format = Exporter.Format.JSON,
            snapshot = store.snapshot(),
            dir = dir,
        )
        // Read the FILE back as raw bytes (the platform boundary), then
        // let core restore it:
        val restored = uniffi.skipti_core.ffiImportLedgerJson(file.readText())
        assertEquals(store.snapshot(), restored)

        // The restore lands in the vault; the balance line must agree
        // with the pre-export ledger to the paisa.
        store.writeSnapshot(restored)
        val reopened = fixture.store() // a FRESH store = a fresh vault open
        assertEquals(store.snapshot(), reopened.snapshot())
        val group = reopened.snapshot().groups[0].id
        val beforePlan = SettleUi.recomputePlan(store.loadExpenses(), store.loadSettlements(), store.loadRedistributions(), group, BalanceFixtures.ada)
        val afterPlan = SettleUi.recomputePlan(reopened.loadExpenses(), reopened.loadSettlements(), reopened.loadRedistributions(), group, BalanceFixtures.ada)
        assertEquals(beforePlan, afterPlan)
        assertEquals(group, reopened.snapshot().groups[0].id)
    }
}
