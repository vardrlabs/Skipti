package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.LocaleTag

/**
 * The balance screen's goldens, driven against the REAL core through the
 * same JNA bindings the app uses (host dylib from `cargo build -p
 * skipti-core`; see app/build.gradle.kts testOptions). §11.4's worked
 * example is the test, so a D-47-class sign inversion can never pass again,
 * and the mixed-direction fixture proves the viewer frame in BOTH
 * directions, which the pure-positive example alone cannot.
 */
class BalanceScreenTest {

    private val inr = LocaleTag.EN_IN

    /** §11.4's worked example, verbatim: the Goa dinner alone. */
    private fun workedExample(): List<ExpenseInput> = listOf(
        ExpenseInput(
            payer = BalanceFixtures.ada,
            paidMinor = 428_000,
            currencyCode = "INR",
            shares = listOf(
                IdAmount(id = BalanceFixtures.rahul, amountMinor = 210_000),
                IdAmount(id = BalanceFixtures.priya, amountMinor = 148_000),
                IdAmount(id = BalanceFixtures.amit, amountMinor = 70_000),
            ),
            group = null,
            conversion = null,
        ),
    )

    private fun lineFor(expenses: List<ExpenseInput>, group: String?): BalanceLineOut =
        BalanceUi.computeLines(expenses, group).first { it.currencyCode == "INR" }

    @Test
    fun worked_example_renders_the_114_golden_verbatim() {
        val line = lineFor(workedExample(), group = null)
        assertEquals(428_000L, line.viewerNetMinor)
        // §11.4: RAHUL +2,100 / PRIYA +1,480 / AMIT +700, magnitude order.
        assertEquals(
            listOf(BalanceFixtures.rahul, BalanceFixtures.priya, BalanceFixtures.amit),
            line.entries.map { it.id },
        )
        assertEquals(
            listOf("+2,100", "+1,480", "+700"),
            BalanceUi.renderedDecompositions(line, inr),
        )
        assertEquals("₹4,280.00", BalanceUi.moneyFormat(line.viewerNetMinor, "INR", inr))
    }

    @Test
    fun a_sign_inversion_cannot_pass_the_golden() {
        // The D-47 defect inverted the entries while the headline kept the
        // viewer's net: "RAHUL -2,100" under "you are owed" asserted the
        // inverse of the truth. Both assertions below fail under that bug:
        // every entry must be money COMING to the viewer, printed with the
        // "+" core puts on money coming to you.
        val line = lineFor(workedExample(), group = null)
        assertTrue(
            "viewer is owed, so every entry is positive: ${line.entries}",
            line.entries.all { it.minorUnits > 0 },
        )
        assertTrue(
            "rendered signs must be +, not the inverted -",
            BalanceUi.renderedDecompositions(line, inr).all { it.startsWith("+") },
        )
        assertEquals("you are owed", BalanceUi.captionFor(line.viewerNetMinor))
    }

    @Test
    fun mixed_direction_fixture_proves_the_viewer_frame_both_ways() {
        // The full fixture ledger: conversion at entry (§5.2) collapses the
        // USD expense into the INR line, flipping ada to owing -218,250,
        // so this golden holds BOTH signs at once. A globally flipped frame
        // fails it; a view that negates entries fails it; core's own frame
        // is the only thing that passes.
        val line = lineFor(BalanceFixtures.dto(BalanceFixtures.ada), group = BalanceFixtures.ada)
        assertEquals(-218_250L, line.viewerNetMinor)
        assertEquals(
            listOf("-2,062.50", "-520", "+400"),
            BalanceUi.renderedDecompositions(line, inr),
        )
        assertEquals("you owe", BalanceUi.captionFor(line.viewerNetMinor))
        assertEquals(
            "-₹2,182.50",
            BalanceUi.moneyFormat(line.viewerNetMinor, "INR", inr),
        )
    }

    @Test
    fun empty_ledger_yields_the_116_empty_state_not_a_blank_screen() {
        val lines = BalanceUi.computeLines(emptyList(), group = null)
        assertTrue(lines.isEmpty())
        assertEquals(BalanceUi.State.EMPTY, BalanceUi.stateFor(lines))
        assertEquals(BalanceUi.State.LOADING, BalanceUi.stateFor(null))
        assertEquals(BalanceUi.State.CONTENT, BalanceUi.stateFor(listOf(lineFor(workedExample(), null))))
        assertEquals(
            "Nothing here yet. Add your first expense.",
            BalanceUi.EMPTY_LEDGER_COPY,
        )
    }

    @Test
    fun spoken_labels_are_the_core_goldens_not_the_visible_string() {
        // §11.7: "minus one thousand two hundred and forty rupees", not
        // "dash one two four zero": the TalkBack forms come from
        // ffiMoneySpoken, exactly what the views attach as labels.
        assertEquals(
            "minus one thousand two hundred and forty rupees",
            BalanceUi.moneySpoken(-124_000, "INR", inr),
        )
        assertEquals(
            "two thousand one hundred rupees",
            BalanceUi.moneySpoken(210_000, "INR", inr),
        )
        // And the visible decomposition differs from the spoken form:
        // reading the rendered string aloud is the defect §11.7 names.
        assertFalse(
            BalanceUi.moneyDecomposition(-124_000, "INR", inr) ==
                BalanceUi.moneySpoken(-124_000, "INR", inr),
        )
    }

    @Test
    fun staleness_days_drive_the_52_indicator() {
        val now = 1_756_000_000_000
        assertEquals(3, BalanceUi.stalenessDays(now, BalanceFixtures.dto(BalanceFixtures.ada, now)))
        assertEquals(null, BalanceUi.stalenessDays(now, workedExample()))
        assertEquals(1, BalanceUi.stalenessDays(now, listOf(expenseConvertedAt(now - 24L * 60L * 60L * 1000L))))
        assertEquals(0, BalanceUi.stalenessDays(now, listOf(expenseConvertedAt(now))))
    }

    private fun expenseConvertedAt(rateMs: Long): ExpenseInput = ExpenseInput(
        payer = BalanceFixtures.ada,
        paidMinor = 5_000,
        currencyCode = "USD",
        shares = listOf(IdAmount(id = BalanceFixtures.rahul, amountMinor = 416_250)),
        group = null,
        conversion = uniffi.skipti_core.ConversionInput(
            convertedMinor = 416_250,
            convertedCurrency = "INR",
            rateNum = 8_325,
            rateDen = 100,
            rateTimestampMs = rateMs.toULong(),
        ),
    )

    @Test
    fun header_names_the_group_member_count_and_settlement_currency() {
        val line = lineFor(workedExample(), group = null)
        // D-174 (ADR-0038): sentence case, not an uppercase micro-label.
        assertEquals(
            "Goa trip · 4 members · settles in INR",
            BalanceUi.headerFor(line, BalanceFixtures.GROUP_NAME, BalanceFixtures.MEMBER_COUNT),
        )
        // "1 people" was in the founder's screenshots (2026-09-26).
        assertEquals("Solo · 1 member · settles in INR", BalanceUi.headerFor(line, "Solo", 1))
        assertEquals(
            "Goa trip, 4 members, settled in INR",
            BalanceUi.spokenHeaderFor(line, BalanceFixtures.GROUP_NAME, BalanceFixtures.MEMBER_COUNT),
        )
    }

    @Test
    fun fixture_records_belong_to_the_group_and_carry_the_frozen_rate() {
        val now = 1_756_000_000_000
        val dto = BalanceFixtures.dto(BalanceFixtures.ada, now)
        assertEquals(4, dto.size)
        assertTrue("every fixture expense belongs to the group (§5.3)", dto.all { it.group == BalanceFixtures.ada })
        val usd = dto.first { it.currencyCode == "USD" }
        val conversion = usd.conversion!!
        assertEquals(416_250L, conversion.convertedMinor)
        assertEquals("INR", conversion.convertedCurrency)
        assertEquals(8_325L, conversion.rateNum)
        assertEquals(100L, conversion.rateDen)
        assertEquals(BalanceFixtures.rateTimestampMs(now), conversion.rateTimestampMs.toLong())
    }

    /**
     * D-126's account hub, the pure part.
     *
     * The property that carries a GUARANTEE rather than a preference:
     * with NO account there are no account controls at all. #5 forbids an
     * account being "created, implied, or nudged for", and a disabled
     * sync switch or a greyed-out Devices row IS an implication: it
     * shows someone what they are missing. Absent, not disabled.
     */
    @Test
    fun the_account_hub_shows_no_account_controls_without_an_account() {
        val none = accountHubModel(signedIn = false, syncOn = true, approval = ApprovalState.UNKNOWN)
        assertEquals(AccountState.NO_ACCOUNT, none.state)
        assertFalse("no account must mean NO controls, not disabled ones", none.showsAccountControls)
        val noneOff = accountHubModel(signedIn = false, syncOn = false, approval = ApprovalState.UNKNOWN)
        assertEquals(AccountState.NO_ACCOUNT, noneOff.state)
        assertFalse(noneOff.showsAccountControls)

        // D-192: a signed-in phone has the account. D-254: once approved (or not heard yet); ApprovalTest covers the
        // waiting and not approved states.
        val active = accountHubModel(signedIn = true, syncOn = true, approval = ApprovalState.APPROVED)
        assertEquals(AccountState.ACTIVE, active.state)
        assertTrue(active.showsAccountControls)

        // Sync state rides through untouched: the hub renders it, the
        // switch owns it (D-125).
        assertTrue(accountHubModel(signedIn = true, syncOn = true, approval = ApprovalState.APPROVED).syncOn)
        assertFalse(accountHubModel(signedIn = true, syncOn = false, approval = ApprovalState.APPROVED).syncOn)
    }

    /** D-221 (L-D217-7, Design 14): "you are owed" sits at the row's end, as iOS's `Spacer(minLength: 12)` puts it: the
     *  side-by-side row fills the width, a weighted spacer pushes the caption to the end with a 12 dp minimum gap,
     *  and both keep their shared baseline. The stacked row is unchanged. */
    @Test
    fun the_caption_sits_at_the_rows_end() {
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/BalanceScreen.kt").readText()
        val view = src.substring(src.indexOf("fun BalanceLineView(")).substringBefore("\n}\n")
        val row = view.substringAfter("} else {").substringBefore("Spacer(Modifier.height(10.dp))")
        val amount = row.indexOf("amount(Modifier.alignByBaseline())")
        val spacer = row.indexOf("Spacer(Modifier.weight(1f))")
        val caption = row.indexOf("caption(Modifier.alignByBaseline().padding(start = 12.dp))")
        assertTrue("amount, spacer, caption in order: $amount, $spacer, $caption", amount >= 0 && amount < spacer && spacer < caption)
        assertTrue("the row fills the width", "Row(Modifier.fillMaxWidth())" in row)
        assertFalse("no fixed gap any more", "Spacer(Modifier.width(12.dp))" in row)
        // iOS's own row: the caption after a minimum-12 spacer.
        val ios = java.io.File("../../ios/Skipti/App/BalanceLineView.swift").readText()
        assertTrue("iOS's row", Regex("""amount\(net\)\s*Spacer\(minLength: 12\)\s*caption\(net\)""").containsMatchIn(ios))
    }

    /** D-228: the "no value" placeholder is SKIPTI's own typed hyphen, U+002D, one constant (the twin of iOS's
     *  testThePlaceholderIsAPlainHyphen). */
    @Test
    fun the_placeholder_is_a_plain_hyphen() {
        assertEquals(listOf(0x2D), Placeholder.NONE.map { it.code })
    }

    /** D-228: every fallback of a core money call, and the Overview "Settled" tile, reads [Placeholder.NONE]; no
     *  file writes its own one-character literal (the twin of iOS's testMoneyFallbacksUseThePlaceholder). */
    @Test
    fun money_fallbacks_use_the_placeholder() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val files = dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        val literalFallback = Regex("(catch\\s*\\(_: Exception\\)\\s*\\{\\s*|getOrDefault\\(\\s*)\"[^\"]\"")
        var uses = 0
        for (f in files) {
            val text = f.readText()
            assertFalse("${f.name}: a money fallback writes its own literal", literalFallback.containsMatchIn(text))
            uses += Regex("Placeholder\\.NONE\\b").findAll(text).count()
        }
        // Five since decision 43: the spoken money fallback is "" now, not the placeholder.
        assertTrue("the five sites read the constant: $uses", uses >= 5)
        val overview = java.io.File(dir, "OverviewTab.kt").readText()
        assertTrue("the Settled tile shows the placeholder", "Stat(OverviewCopy.SETTLED_SHORT, Placeholder.NONE," in overview)
    }

    /** D-228: the recorded settle sentence and the placeholder, shown and spoken, carry neither dash (the twin of the
     *  iOS test of the same copy in BalanceLineTests). */
    @Test
    fun recorded_copy_and_placeholder_have_no_dash() {
        val dashes = setOf(Char(0x2000 + 0x14), Char(0x2000 + 0x13)) // U+2014 and U+2013, built by arithmetic
        for (s in listOf(SettleAllCopy.recorded("Ana"), Placeholder.NONE, Placeholder.SPOKEN_NONE)) {
            assertTrue("no dash in $s", s.none { it in dashes })
        }
    }

    /** D-228 decision 43: the placeholder shown as a value is SPOKEN as "none", never "hyphen" or "minus" (the twin of
     *  iOS's `Placeholder.spokenNone`); any other value is spoken as shown. */
    @Test
    fun the_placeholder_is_spoken_as_none() {
        assertEquals("none", Placeholder.SPOKEN_NONE)
        assertEquals("none", Placeholder.spoken("-"))
        assertEquals("$1.00", Placeholder.spoken("$1.00"))
        assertEquals("-1", Placeholder.spoken("-1"))
        assertEquals("", Placeholder.spoken(""))
    }

    /** D-228 decision 43 (final review I1): when core cannot speak an amount, the spoken fallback is "", as on iOS
     *  (every `ffiMoneySpoken` there falls back to `?? ""`), never the placeholder, which TalkBack could read as a
     *  minus. The shown fallback stays the placeholder. */
    @Test
    fun the_spoken_money_fallback_is_empty() {
        assertEquals("core refuses a made-up currency: spoken", "", BalanceUi.moneySpoken(100, "ZZZ", LocaleTag.EN_US))
        assertEquals("core refuses a made-up currency: shown", Placeholder.NONE, BalanceUi.moneyFormat(100, "ZZZ", LocaleTag.EN_US))
        assertTrue("a real currency still speaks", BalanceUi.moneySpoken(100, "USD", LocaleTag.EN_US).isNotEmpty())
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/BalanceScreen.kt").readText()
        assertTrue(
            "the spoken fallback is the empty string",
            """ffiMoneySpoken(minor, currencyCode, locale) } catch (_: Exception) { "" }""" in src,
        )
    }

    /** D-228 decision 43: the sites that show the placeholder as a whole value set a spoken label through
     *  [Placeholder.spoken]: the Overview stat tile (the Settled tile and its money fallbacks) and the import proposal
     *  row (its amount fallback). */
    @Test
    fun placeholder_sites_speak_none() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val overview = java.io.File(dir, "OverviewTab.kt").readText()
        assertTrue(
            "the stat tile speaks its value through Placeholder.spoken",
            "contentDescription = \"\$label \${Placeholder.spoken(value)}\"" in overview,
        )
        val flow = java.io.File(dir, "ImportFlow.kt").readText()
        assertTrue(
            "the proposal row speaks its amount through Placeholder.spoken",
            "\${p.noteText ?: \"untitled\"}, \${Placeholder.spoken(p.amountText)} \${p.currencyCode}" in flow,
        )
    }

    /** D-228: the import screens flag a duplicate as "Duplicate: <label>", shown and spoken, on both shells (the twin
     *  of iOS's pin of ImportReview.swift, which reads this shell's sources). */
    @Test
    fun duplicate_kicker_reads_duplicate_colon() {
        val dir = java.io.File("src/main/java/com/vardrlabs/skipti")
        val flow = java.io.File(dir, "ImportFlow.kt").readText()
        assertTrue("ImportFlow shows the kicker", "Type.kicker(\"Duplicate: \${it.label}\")" in flow)
        assertTrue("ImportFlow speaks it", "\". Duplicate: \${it.label}\"" in flow)
        val proof = java.io.File(dir, "ImportScreen.kt").readText()
        assertTrue("ImportScreen shows the kicker", "Type.kicker(\"Duplicate: \${d.label}\")" in proof)
        val ios = java.io.File("../../ios/Skipti/App/ImportReview.swift").readText()
        assertTrue("iOS shows and speaks the same words", Regex("""Duplicate: \\\(""").findAll(ios).count() >= 2)
    }
}
