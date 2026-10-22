package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.RedistributionInput
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.TransferOut

/**
 * Computed data for §5.8's settle-up screen: the pure decisions the JVM
 * tests drive against the REAL core (same discipline as BalanceUi /
 * ExpenseListUi / AddExpenseUi). THE LEGAL BOUNDARY LIVES IN THESE STRINGS:
 * SKIPTI records that a debt was settled; it never moves money, never holds
 * funds, never links an account (ADR-0008, §13.2). Everything here passes
 * §11.6's copy rules; the explainer sentence is verbatim.
 */
object SettleUi {
    /** §11.6, verbatim: the sentence that keeps SKIPTI a notebook. */
    const val EXPLAINER = "Marks this as paid here. It does not move money."

    /** Sentence-position name: the viewer is "you", never "You". */
    fun you(id: String, viewer: String, names: Map<String, String>): String =
        if (id == viewer) "you" else (names[id] ?: id)

    /** §11.6's verb rule: each branch keeps its own progression: Settle →
     *  Settling…, Mark received → Receiving…. Never one verb morphing into
     *  the other's. */
    fun buttonLabel(youOwe: Boolean, settling: Boolean): String = when {
        settling && youOwe -> "Settling…"
        settling -> "Receiving…"
        youOwe -> "Settle"
        else -> "Mark received"
    }

    /** §11.6: sentence case, terse; the verb agrees with its subject and
     *  the viewer's subject is "you". The visible copy and the spoken label
     *  say the SAME words, including the verb (iOS's spoken label said
     *  "you pays Rahul"; agreement is the requirement here). */
    fun planRow(from: String, to: String, viewer: String, names: Map<String, String>): String {
        val verb = if (from == viewer) "pay" else "pays"
        return "${you(from, viewer, names)} $verb ${you(to, viewer, names)}"
    }

    fun planSpoken(from: String, to: String, viewer: String, names: Map<String, String>): String =
        "${planRow(from, to, viewer, names)}."

    /** The debt row's micro-label frame, viewer-framed (D-47). */
    fun debtLabel(entry: ShareOut, viewer: String, names: Map<String, String>): String =
        if (entry.minorUnits < 0) {
            "You owe ${names[entry.id] ?: entry.id}"
        } else {
            "${names[entry.id] ?: entry.id} owes you"
        }

    fun debtSpoken(entry: ShareOut, viewer: String, names: Map<String, String>): String =
        if (entry.minorUnits < 0) {
            "You owe ${names[entry.id] ?: entry.id}."
        } else {
            "${names[entry.id] ?: entry.id} owes you."
        }

    private const val VIEWER_KEY = "__viewer__"

    /**
     * §5.8's plan, pinned as a property rather than reimplemented: the
     * transfers CONSERVE the ledger's absolute net positions: applying
     * them zeroes every net (§5.6's net formula applied by the CALLER; the
     * viewer-frame line alone cannot reconstruct an uninvolved viewer's
     * ledger, which is why this takes the nets). A settlement-shaped or
     * simplification-shaped bug corrupts exactly this. ≤n−1 legs is core's
     * own guarantee (§5.6), pinned separately by a golden where naive
     * pairwise would need more transfers.
     */
    fun planConserves(nets: Map<String, Long>, transfers: List<TransferOut>): Boolean {
        val applied = nets.toMutableMap()
        for (t in transfers) {
            applied[t.from] = (applied[t.from] ?: 0L) + t.amountMinor
            applied[t.to] = (applied[t.to] ?: 0L) - t.amountMinor
        }
        return applied.values.all { it == 0L }
    }

    /**
     * The recomputed plan, THE ONE PATH: the screen uses this and so do the
     * JVM tests: a settlement-shaped bug (or a Kotlin patch that skips the
     * settlements) turns the same test red that the screen depends on.
     * Settlements come from the vault; the settled state is what core says,
     * never a UI flag.
     */
    fun recomputePlan(
        expenses: List<ExpenseDTO>,
        settlements: List<SettlementDTO>,
        redistributions: List<RedistributionInput>,
        groupId: String?,
        viewer: String,
        removed: List<String> = emptyList(),
    ): List<BalanceLineOut> =
        BalanceUi.sortedLines(
            ffiBalanceLine(
                expenses.map { it.toInput() },
                settlements.map { it.toInput() },
                redistributions,
                removed,
                viewer,
                groupId,
            )
        )

    /**
     * The settlement a Settle tap records: between the VIEWER (this device's
     * self, D-136; it was the fixture Ada) and the other person, in the
     * direction the viewer-framed entry says. Negative means you owe them.
     */
    fun settlementFor(entry: ShareOut, currency: String, group: String?, viewer: String): SettlementDTO {
        val youOwe = entry.minorUnits < 0
        return SettlementDTO(
            from = if (youOwe) viewer else entry.id,
            to = if (youOwe) entry.id else viewer,
            amountMinor = abs(entry.minorUnits),
            currencyCode = currency,
            group = group,
        )
    }
}
