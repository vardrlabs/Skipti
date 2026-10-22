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
