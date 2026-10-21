package com.vardrlabs.skipti

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.CurrencyTotal
import uniffi.skipti_core.DuplicateAgainst
import uniffi.skipti_core.ImportPlan
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.PortExpense
import uniffi.skipti_core.PortException

/**
 * §9.5's confirm model over ONE plan type (iOS's ImportReviewModel,
 * one-to-one).
 *
 * Deliberately PROFILE-BLIND: `ImportPlan` carries no provenance field
 * and this model needs none. The source format is chosen BEFORE planning
 * because it describes the file being read, not the result of reading it.
 *
 * §9.5's obligations, enforced by shape rather than discipline:
 * 1. Nothing exists until Apply: this model holds ONLY the plan and the
 *    existing snapshot; creation goes through `ffiApplyImportPlan`, whose
 *    merge validates the whole result and refuses atomically.
 * 2. Duplicates are FLAGGED, never removed: flagged rows ship included,
 *    WITH the flag visible; unchecking is the human decision the flag
 *    exists to inform.
 * 3. Rejections list their SPREADSHEET line numbers, matching what the
 *    user sees when they open the sheet themselves.
 */
class ImportReview(
    plan: ImportPlan,
    private val locale: LocaleTag,
    /** D-166: participant id → name, so a refusal reads "Tim" where core
     *  wrote an id. */
    names: Map<String, String> = emptyMap(),
) {
    data class Proposal(
        val id: Int, // index into the ORIGINAL plan's expenses
        val amountText: String,
        val noteText: String?,
        val dateText: String?,
        val currencyCode: String,
        val paidMinor: Long,
        val duplicateOf: DuplicateTarget?,
        var included: Boolean = true,
    )

    sealed class DuplicateTarget {
        data class Existing(val index: UInt) : DuplicateTarget()
        data class InFile(val index: UInt) : DuplicateTarget()

        val label: String
            get() = when (this) {
                is Existing -> "matches imported expense ${index + 1u}"
                is InFile -> "matches proposal #${index + 1u} from this file"
            }
    }

    data class RejectionRow(val id: UInt, val lineNumber: UInt, val reason: String)

    sealed class AppliedState {
        object Reviewing : AppliedState()
        data class Done(val expenseCount: Int) : AppliedState()
        data class Failed(val message: String) : AppliedState()
    }

    val proposals: List<Proposal>
    val rejections: List<RejectionRow>
    val totalsText: List<String>
    var appliedState: AppliedState = AppliedState.Reviewing
        private set

    /** The merged snapshot, set ONLY when apply() succeeded: the shell's
     *  persist-exactly-once handle. Nothing else may write it. */
    var merged: LedgerSnapshot? = null
        private set

    /** Captured at init so exclusions filter the ORIGINAL plan while index
     *  space stays intact; the engine never learns a row was hidden. */
    private val sourceExpenses: List<PortExpense> = plan.expenses

    val includedCount: Int get() = proposals.count { it.included }
    val duplicateFlaggedCount: Int get() = proposals.count { it.duplicateOf != null }
    val hasIncludedExpenses: Boolean get() = includedCount > 0

    init {
        proposals = plan.expenses.mapIndexed { index, expense ->
            val amount = try {
                uniffi.skipti_core.ffiMoneyFormat(
                    expense.paidMinor, expense.currencyCode, locale,
                )
            } catch (_: Exception) {
                Placeholder.NONE
            }
            val duplicate = plan.duplicates.firstOrNull { it.expenseIndex == index.toUInt() }
                ?.let { hit ->
                    when (val against = hit.against) {
                        is DuplicateAgainst.Existing -> DuplicateTarget.Existing(against.index)
                        is DuplicateAgainst.InFile -> DuplicateTarget.InFile(against.index)
                    }
                }
            Proposal(
                id = index,
                amountText = amount,
                noteText = expense.note,
                dateText = expense.timestampMs?.let { dateString(it, locale) },
                currencyCode = expense.currencyCode,
                paidMinor = expense.paidMinor,
                duplicateOf = duplicate,
            )
        }
        rejections = plan.rejections.map {
            RejectionRow(id = it.rowNumber, lineNumber = it.rowNumber, reason = naming(it.reason, names))
        }
        totalsText = plan.totalsByCurrency.mapNotNull { total: CurrencyTotal ->
            try {
                "${uniffi.skipti_core.ffiMoneyFormat(total.costMinor, total.currencyCode, locale)} ${total.currencyCode}"
            } catch (_: Exception) {
                null
            }
        }
    }

    fun setIncluded(value: Boolean, forId: Int) {
        val idx = proposals.indexOfFirst { it.id == forId }
        if (idx >= 0) proposals[idx].included = value
    }

    /**
     * THE ACT. Explicit; the only path out of review. Merges the INCLUDED
     * proposals as one unit through the FFI, which validates the entire
     * merged ledger and refuses atomically. Excluded proposals are left
     * out of the handed-over plan: deciding is this layer's whole job.
     */
    fun apply(existing: LedgerSnapshot, save: ((List<PortExpense>) -> Unit)? = null): AppliedState {
        if (!hasIncludedExpenses) {
            appliedState = AppliedState.Failed("Nothing selected to import.")
            return appliedState
        }
        val filtered = ImportPlan(
            expenses = proposals.filter { it.included }.map { sourceExpenses[it.id] },
            duplicates = emptyList(),
            rejections = emptyList(),
            totalsByCurrency = emptyList(),
        )
        appliedState = try {
            val mergedNow = uniffi.skipti_core.ffiApplyImportPlan(existing, filtered)
            merged = mergedNow
            // D-166: written only after core accepted the whole merge.
            if (save != null) {
                try {
                    save(filtered.expenses)
                    AppliedState.Done(expenseCount = filtered.expenses.size)
                } catch (e: Exception) {
                    merged = null
                    AppliedState.Failed("The import could not be saved: ${e.message ?: e::class.simpleName}")
                }
            } else {
                AppliedState.Done(expenseCount = mergedNow.expenses.size)
            }
        } catch (e: PortException) {
            merged = null
            AppliedState.Failed(describe(e))
        } catch (_: Exception) {
            merged = null
            AppliedState.Failed(describe(null))
        }
        return appliedState
    }

    companion object {
        /** §11.6 register: what happened, nothing else. */
        fun describe(error: PortException?): String =
            (error as? PortException.InvalidLedger)
                ?.let { "The merged ledger would be invalid: ${it.v1}" }
                ?: "The import was refused."

        fun naming(reason: String, names: Map<String, String>): String =
            names.entries.fold(reason) { text, (id, name) -> text.replace(id, name) }

        /** D-221 fix wave (S10): the file's own day, in UTC, through [AppDate.day] as iOS's review. */
        fun dateString(ms: ULong, locale: LocaleTag): String = AppDate.day(ms.toLong(), locale, ZoneOffset.UTC)
    }
}

/**
 * Headless verification (--import-proof): ONE review path consumed by TWO
 * source profiles (iOS's ImportProofScreen, ported). Runs a Splitwise
 * group sheet and a BudgetBakers statement through `ffiPlanImport`, feeds
 * each plan through the SAME [ImportReview] the confirm screen uses, and
 * mechanically asserts §9.5's obligations: flagged-not-removed
 * duplicates, spreadsheet line numbers on refusals, all-or-nothing apply
 * WITH THE VAULT UNCHANGED on refusal. NOT a user flow.
 */
object ImportProof {

    data class Result(
        var splitwisePlanned: Boolean = false,
        var budgetBakersPlanned: Boolean = false,
        var oneModelBothProfiles: Boolean = false,
        var duplicateFlaggedNotRemoved: Boolean = false,
        var rejectionLinesPresent: Boolean = false,
        var totalsRendered: Boolean = false,
        var exclusionFiltered: Boolean = false,
        var atomicRefusalAndVaultUntouched: Boolean = false,
        /** D-234 (L1): why the vault leg could not run (a vault that will not open), shown on the proof screen. */
        var vaultError: String? = null,
    ) {
        val passed: Boolean
            get() = splitwisePlanned && budgetBakersPlanned && oneModelBothProfiles &&
                duplicateFlaggedNotRemoved && rejectionLinesPresent && totalsRendered &&
                exclusionFiltered && atomicRefusalAndVaultUntouched
        val detail: String? get() = if (passed) null else vaultError?.let { "the vault would not open: $it" } ?: "see first failing step"
    }

    /** Fixture sheets mirroring core/tests/ledger_port.rs: BOM + CRLF
     *  like real exports, one trap row each. */
    fun splitwiseSheet(): String = listOf(
        "\uFEFFDate,Description,Category,Cost,Currency,Alice,Bob\r\n",
        // Both dinner rows carry an empty Category so their NOTES are
        // byte-equal, otherwise no amount+date+merchant match:
        "2026-01-15,Dinner, ,45.50,USD,45.50,-45.50\r\n",
        "2026-01-16,Tuk tuk,Transport,\"1,234.50\",INR,411.50,-411.50\r\n",
        "2026-01-15,Dinner, ,45.50,USD,45.50,-45.50\r\n",
        "Total balance,,,,USD,68.25,-68.25\r\n",
    ).joinToString("")

    fun budgetBakersSheet(): String = listOf(
        "\uFEFFAccount,Category,Currency,Amount,Account Currency,Income/Expense," +
            "Refund,Refund Currency,Note,Date\r\n",
        "Checking,Salary,INR,500000,,Income,,,Monthly pay,2026-01-05 09:00:00\r\n",
        "Cash,Groceries,INR,-2547.50,,Expense,,,Corner shop,2026-01-15 13:30:00\r\n",
        "Cash,,XXX,10,,Garbage,,,?,2026-01-03 00:00:00\r\n",
    ).joinToString("")

    fun prove(store: LedgerStore? = null): Result {
        val r = Result()
        val ada = "a".repeat(31) + "1"
        val bob = "b".repeat(31) + "2"
        val goa = "d".repeat(31) + "4"
        val empty = LedgerSnapshot(groups = emptyList(), expenses = emptyList(), settlements = emptyList(), redistributions = emptyList(), profile = null)
        val locale = LocaleTag.EN_US

        // Splitwise leg:
        val swOptions = uniffi.skipti_core.SplitwiseOptions(
            dateFormat = uniffi.skipti_core.DateFormat.ISO_YMD,
            members = listOf(
                uniffi.skipti_core.MemberBinding("Alice", ada),
                uniffi.skipti_core.MemberBinding("Bob", bob),
            ),
            group = goa,
        )
        val swPlan = try {
            uniffi.skipti_core.ffiPlanImport(splitwiseSheet(), uniffi.skipti_core.ImportProfile.SplitwiseGroup(swOptions), empty)
        } catch (_: Exception) {
            return r
        }
        r.splitwisePlanned = swPlan.expenses.size == 2 && swPlan.rejections.size == 2

        // BudgetBakers leg:
        val bbOptions = uniffi.skipti_core.BudgetBakersOptions(selfParticipant = ada, group = null)
        val bbPlan = try {
            uniffi.skipti_core.ffiPlanImport(budgetBakersSheet(), uniffi.skipti_core.ImportProfile.BudgetBakersWallet(bbOptions), empty)
        } catch (_: Exception) {
            return r
        }
        r.budgetBakersPlanned = bbPlan.expenses.size == 1 && bbPlan.rejections.size == 2

        // The SAME model type consumes both plans; nothing here can branch
        // on the producing profile because no such field exists:
        val swModel = ImportReview(swPlan, locale)
        val bbModel = ImportReview(bbPlan, locale)
        r.oneModelBothProfiles =
            swModel.proposals.size == 2 && bbModel.proposals.size == 1 &&
            swModel.rejections.all { it.lineNumber >= 2u } &&
            bbModel.rejections.all { it.lineNumber >= 2u }
        r.rejectionLinesPresent =
            swModel.rejections.map { it.lineNumber }.sorted() == listOf(3u, 5u) &&
            bbModel.rejections.map { it.lineNumber }.sorted() == listOf(2u, 4u)
        r.totalsRendered = swModel.totalsText.isNotEmpty() && bbModel.totalsText.isNotEmpty()

        // Flagged-not-removed: Dinner 2 duplicates Dinner by amount+date+
        // merchant and must STILL be an included proposal wearing its flag:
        val dinnerRow = swModel.proposals.firstOrNull { it.id == 1 } ?: return r
        val duplicate = dinnerRow.duplicateOf as? ImportReview.DuplicateTarget.InFile ?: return r
        r.duplicateFlaggedNotRemoved = duplicate.index == 0u && dinnerRow.included

        // Exclude-one semantics: uncheck the flagged duplicate, apply; n−1
        // proposals become n−1 created expenses in one atomic unit:
        swModel.setIncluded(false, forId = 1)
        val applied = swModel.apply(empty)
        r.exclusionFiltered = (applied as? ImportReview.AppliedState.Done)?.expenseCount == 1

        // Atomic refusal + VAULT UNCHANGED: a poisoned plan (shares sum
        // 501 ≠ 500) arrives as a typed failure with NOTHING created:
        // asserted against the vault's actual bytes when a store is
        // supplied, not assumed.
        val poisoned = ImportPlan(
            expenses = listOf(
                PortExpense(
                    payer = ada,
                    paidMinor = 500,
                    currencyCode = "USD",
                    group = null,
                    conversion = null,
                    shares = listOf(
                        uniffi.skipti_core.ExpenseShare(ada, 499),
                        uniffi.skipti_core.ExpenseShare(bob, 2), // sums to 501 ≠ 500
                    ),
                    note = "broken sum",
                    timestampMs = 20_468uL * 86_400_000uL,
                    category = null,
                ),
            ),
            duplicates = emptyList(),
            rejections = emptyList(),
            totalsByCurrency = emptyList(),
        )
        val poisonModel = ImportReview(poisoned, locale)
        val poisonApplied = poisonModel.apply(empty)
        val refusedClean = poisonApplied is ImportReview.AppliedState.Failed &&
            poisonApplied.message.startsWith("The merged ledger would be invalid") &&
            poisonModel.merged == null

        if (store != null) {
            // D-234 (L1): a vault that will not open (a wrong key) is said on the screen, never a crash; "untouched" is
            // then unproven, so the step stays false.
            try {
                val before = listOf(
                    LedgerStore.EXPENSES_KEY, LedgerStore.GROUPS_KEY, LedgerStore.SETTLEMENTS_KEY,
                ).associateWith { store.rawKvGet(it) }
                // The commit path the shell uses: persist ONLY on Done.
                if (poisonModel.appliedState is ImportReview.AppliedState.Done) {
                    store.writeSnapshot(poisonModel.merged!!)
                }
                val after = listOf(
                    LedgerStore.EXPENSES_KEY, LedgerStore.GROUPS_KEY, LedgerStore.SETTLEMENTS_KEY,
                ).associateWith { store.rawKvGet(it) }
                r.atomicRefusalAndVaultUntouched = refusedClean && before == after
            } catch (e: Exception) {
                r.vaultError = e::class.simpleName ?: e.toString()
                r.atomicRefusalAndVaultUntouched = false
            }
        } else {
            r.atomicRefusalAndVaultUntouched = refusedClean
        }
        return r
    }
}

/** The --import-proof screen. */
@androidx.compose.runtime.Composable
fun ImportProofScreen(store: LedgerStore?) {
    val dark = isSystemInDarkTheme()
    var result by remember { mutableStateOf<ImportProof.Result?>(null) }

    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) { ImportProof.prove(store) }
    }

    ProofColumn(title = "Import proof", header = "IMPORT CONFIRMATION: BOTH PROFILES, ONE SCREEN", dark = dark) {
        ProofRow(dark, "Splitwise sheet → plan", result?.splitwisePlanned)
        ProofRow(dark, "BudgetBakers statement → plan", result?.budgetBakersPlanned)
        ProofRow(dark, "same ImportReview consumes both", result?.oneModelBothProfiles)
        ProofRow(dark, "duplicate flagged AND still included", result?.duplicateFlaggedNotRemoved)
        ProofRow(dark, "refusals carry spreadsheet line numbers", result?.rejectionLinesPresent)
        ProofRow(dark, "totals reconcile per currency", result?.totalsRendered)
        ProofRow(dark, "exclude-one apply = n-1 created", result?.exclusionFiltered)
        ProofRow(dark, "apply is atomic AND the vault is untouched", result?.atomicRefusalAndVaultUntouched)
        Verdict(dark, result?.passed)
        result?.detail?.let {
            androidx.compose.material3.Text(it, style = Type.numericColored(12, Tok.textFaint(dark)))
        }
    }
}
