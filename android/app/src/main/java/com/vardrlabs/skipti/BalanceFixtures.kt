package com.vardrlabs.skipti

import uniffi.skipti_core.ConversionInput
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.IdAmount

/**
 * The fixture ledger behind the balance and list screens: a value-for-value
 * port of `Fixtures` in ios/Skipti/App/BalanceScreen.swift: §11.4's worked
 * example PLUS three extra expenses that create a mixed-direction ledger:
 * ada owes Priya ₹520 net (the settle-up direction) while Rahul (₹2,100) and
 * Amit (₹400) owe ada. Real core computation, fixture inputs.
 *
 * Expenses BELONG to the group (§5.3): every fixture record carries the
 * group id. The fixture rate is 3 days old on purpose so the §5.2/§11.2
 * staleness indicator is verifiable on screen, not just in tests.
 *
 * The DTO records ARE the fixture (as on iOS, where Fixtures.dto returns
 * ExpenseDTO values); the FFI inputs derive from them, so the
 * fixture path and the vault seed cannot drift.
 */
object BalanceFixtures {
    val ada: String = "a".repeat(31) + "1"
    val rahul: String = "a".repeat(31) + "2"
    val priya: String = "a".repeat(31) + "3"
    val amit: String = "a".repeat(31) + "4"

    val names: Map<String, String> = mapOf(
        ada to "You", rahul to "Rahul", priya to "Priya", amit to "Amit",
    )

    val GROUP_NAME: String? = null // no group store yet: iOS's "GOA TRIP" fallback renders
    const val MEMBER_COUNT: Int = 4

    fun rateTimestampMs(nowMs: Long): Long = nowMs - 3L * 24L * 60L * 60L * 1000L

    /**
     * The four fixture records, with §5.7's note + timestamp (what the vault
     * persists). The USD record enters in USD and is converted AT ENTRY to
     * the group's settlement currency (§5.2): rate 83.25 INR/USD frozen into
     * the record with its own timestamp ($50 → ₹4,162.50), so core collapses
     * it into the INR line instead of splitting the balance across currencies
     * (D-50/D-52's model).
     */
    fun dtoRecords(groupID: String, nowMs: Long = System.currentTimeMillis()): List<ExpenseDTO> = listOf(
        // ₹4,280.00 owed to ada (§11.4's example).
        ExpenseDTO(
            payer = ada,
            paidMinor = 428_000,
            currencyCode = "INR",
            group = groupID,
            conversion = null,
            shares = listOf(
                ExpenseDTO.ShareDTO(id = rahul, amountMinor = 210_000),
                ExpenseDTO.ShareDTO(id = priya, amountMinor = 148_000),
                ExpenseDTO.ShareDTO(id = amit, amountMinor = 70_000),
            ),
            note = "Dinner in Goa",
            timestampMs = nowMs - 2L * 60L * 60L * 1000L,
            category = null,
        ),
        // Amit covers ada's ₹300 → his net debt drops to ₹400.
        ExpenseDTO(
            payer = amit,
            paidMinor = 30_000,
            currencyCode = "INR",
            group = groupID,
            conversion = null,
            shares = listOf(ExpenseDTO.ShareDTO(id = ada, amountMinor = 30_000)),
            note = "Amit covered the taxi",
            timestampMs = nowMs - 26L * 60L * 60L * 1000L,
            category = null,
        ),
        // Priya covers ada's ₹2,000 → flips her net: ada owes HER ₹520.
        // This is the settle-up direction.
        ExpenseDTO(
            payer = priya,
            paidMinor = 200_000,
            currencyCode = "INR",
            group = groupID,
            conversion = null,
            shares = listOf(ExpenseDTO.ShareDTO(id = ada, amountMinor = 200_000)),
            note = "Priya covered the hotel",
            timestampMs = nowMs - 2L * 24L * 60L * 60L * 1000L,
            category = null,
        ),
        // A second ORIGINAL currency, converted at entry (§5.2): the balance
        // line must never render it as a separate USD debt (D-50/D-52).
        ExpenseDTO(
            payer = rahul,
            paidMinor = 5_000,
            currencyCode = "USD",
            group = groupID,
            conversion = ConversionDTO(
                convertedMinor = 416_250,
                convertedCurrency = "INR",
                rateNum = 8_325,
                rateDen = 100,
                rateTimestampMs = rateTimestampMs(nowMs),
            ),
            shares = listOf(ExpenseDTO.ShareDTO(id = ada, amountMinor = 416_250)),
            note = "Rahul paid the USD hotel",
            timestampMs = nowMs - 3L * 24L * 60L * 60L * 1000L,
            category = null,
        ),
    )

    /** The FFI inputs the fixture path computes from. */
    fun dto(groupID: String, nowMs: Long = System.currentTimeMillis()): List<ExpenseInput> =
        dtoRecords(groupID, nowMs).map { it.toInput() }
}
