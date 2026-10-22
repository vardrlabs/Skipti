package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ProjectedExpenseOut

// D-260 (G-13, decision 269): a proof surface moved out of src/main, Debug builds only (src/release has none).

/** The `--expense-conflict` proof's vault: a fresh one under `dir`, an
 *  expense of ₹1,200 and two amount edits with empty `seen`. Returns the
 *  vault path, the expense id and the group the page names people from. */
fun ExpenseDetailUi.seedConflict(dir: java.io.File): Triple<String, String, NamedGroupWire> {
    val path = java.io.File(dir.apply { deleteRecursively(); mkdirs() }, "ledger-vault.db").absolutePath
    val store = LedgerStore(path)
    // D-249 fix wave (decision 208): a write names only a held group's people, so the throwaway vault holds the
    // proof fixture group (core-made id) and the proof's two people are its first two members.
    val held = store.loadGroupsOrSeed().first()
    val asha = held.members[0]
    val ravi = held.members[1]
    val group = held.id
    fun money(minor: Long): uniffi.skipti_core.ExpenseInput = uniffi.skipti_core.ExpenseInput(
        payer = asha, paidMinor = minor, currencyCode = "INR",
        shares = uniffi.skipti_core.ffiSplitCompute(minor, "INR", listOf(asha, ravi), uniffi.skipti_core.SplitSpecInput.Equal)
            .map { uniffi.skipti_core.IdAmount(id = it.id, amountMinor = it.minorUnits) },
        group = group, conversion = null,
    )
    val first = money(120_000)
    store.appendExpense(
        ExpenseDTO(
            payer = asha, paidMinor = first.paidMinor, currencyCode = "INR", group = group,
            conversion = null,
            shares = first.shares.map { ExpenseDTO.ShareDTO(id = it.id, amountMinor = it.amountMinor) },
            note = "Dinner", timestampMs = System.currentTimeMillis(), category = null, addedBy = asha,
        ),
    )
    val id = store.projectedExpenses().expenses.first().id
    // Two edits that did not see each other: empty `seen` on both.
    listOf(125_000L to asha, 130_000L to ravi).forEach { (minor, editor) ->
        store.reviseExpense(
            uniffi.skipti_core.ExpenseRevisionIn(
                target = id, seen = emptyList(), editor = editor, money = money(minor),
                note = null, category = null, deleted = null,
            ),
        )
    }
    val named = NamedGroupWire(
        group = uniffi.skipti_core.GroupWire(id = group, name = "Goa", settlementCurrency = "INR", members = listOf(asha, ravi)),
        selfId = asha,
        members = listOf(uniffi.skipti_core.MemberWire(id = asha, name = "Asha"), uniffi.skipti_core.MemberWire(id = ravi, name = "Ravi")),
    )
    return Triple(path, id, named)
}

/**
 * D-143's proof surface (`--expense-conflict`; a test entry point, not a user
 * flow), the twin of iOS's. One device cannot produce an amount conflict,
 * because each edit it makes has seen the one before, so this seeds a
 * THROWAWAY vault in the cache directory with two amount edits that did not
 * see each other, then shows the real detail page over it. The person's own
 * ledger is never opened for writing.
 */
@Composable
fun ExpenseConflictProofScreen(dir: java.io.File) {
    var seeded by remember { mutableStateOf<Triple<String, String, NamedGroupWire>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dir) {
        runCatching { withContext(Dispatchers.IO) { ExpenseDetailUi.seedConflict(dir) } }
            .fold(onSuccess = { seeded = it }, onFailure = { error = it.message ?: it.toString() })
    }
    val s = seeded
    if (s == null) {
        Text(error ?: "…", style = Type.bodyDim(isSystemInDarkTheme()), modifier = Modifier.padding(16.dp))
    } else {
        ExpenseDetailScreen(
            vaultPath = s.first, expenseId = s.second,
            onBack = {}, onEdit = {}, onDeleted = {}, named = s.third,
        )
    }
}
