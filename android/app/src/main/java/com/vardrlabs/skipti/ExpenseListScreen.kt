package com.vardrlabs.skipti

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.CategoryTree
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.ffiCategoryTreeDefault
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable

/**
 * Computed data for §5.3's list: pure decisions the JVM tests drive against
 * the REAL core (same discipline as BalanceUi). Every amount comes from core
 * FFI; the TalkBack label combines note + payer + the ffiMoneySpoken golden.
 */
object ExpenseListUi {
    /** D-223 follow-up (R2): the list's stack spacing, iOS's ScrollView VStack's (12): between the chips and the
     *  sections, and under the Norse ornament. */
    const val SPACING = 12

    /**
     * Newest first; records without timestamps sink to the end, ties by note
     * descending: the Swift sort, mirrored.
     */
    fun sortedNewestFirst(expenses: List<ExpenseDTO>): List<ExpenseDTO> =
        expenses.sortedWith(
            compareByDescending<ExpenseDTO> { it.timestampMs ?: 0L }
                .thenByDescending { it.note ?: "" }
        )

    /** D-143: the same order over core's projection, the id breaking ties so
     *  two expenses with the same time and note keep their places. */
    fun sortedProjected(expenses: List<ProjectedExpenseOut>): List<ProjectedExpenseOut> =
        expenses.sortedWith(
            compareByDescending<ProjectedExpenseOut> { it.timestampMs ?: 0uL }
                .thenByDescending { it.note ?: "" }
                .thenByDescending { it.id }
        )

    /** §11.3 puts dates in the NUMERIC role; the locale axis is AppLocale's
     *  (D-54: dates format as presentation, money stays in core). D-221 fix wave (S10): [AppDate.day], the locale's
     *  own skeleton, as iOS. */
    fun dateString(ms: Long, locale: LocaleTag): String = AppDate.day(ms, locale)

    /**
     * §8.1's category as a row micro-label, resolved from the default tree.
     * Unknown ids, absent parents, or a parent id itself show NOTHING,
     * never a guess (§8.2's rule, applied to labels in D-88).
     */
    fun categoryLabel(tree: CategoryTree, id: String?): String? {
        id ?: return null
        val leaf = tree.categories.firstOrNull { it.id == id } ?: return null
        val parentId = leaf.parent ?: return null
        val parent = tree.categories.firstOrNull { it.id == parentId } ?: return null
        return "${parent.name}${Separator.meta()}${leaf.name}"
    }

    /** D-136: the payer as this device shows them: "Tim (You)" for yourself,
     *  the stored name for anyone else, from the record's own group first;
     *  "Unknown member" when no group has the id. */
    fun payerLabel(expense: ExpenseDTO, groups: List<NamedGroupWire>): String =
        memberLabel(expense.payer, expense.group, groups)

    /** D-143: any member of an expense, named the same way. */
    fun memberLabel(id: String, group: String?, groups: List<NamedGroupWire>): String {
        fun has(g: NamedGroupWire) = g.members.any { it.id == id }
        val named = groups.firstOrNull { it.group.id == group }?.takeIf(::has)
            ?: groups.firstOrNull(::has)
        return named?.let { LedgerStore.label(id, it) } ?: "Unknown member"
    }

    /** The row's TalkBack label: note (or "Expense"), payer, spoken amount. */
    fun rowLabel(note: String?, payerName: String, spoken: String): String =
        "${note ?: "Expense"}. Paid by $payerName. $spoken."
}

/**
 * §5.3's Transaction list: the records BEHIND the balance line. Port of
 * ios/Skipti/App/ExpenseListView.swift: reads the VAULT (--list-vault),
 * the persisted truth, not fixtures, newest first, with §5.7's attribution
 * (who paid) and §11.3's numeric role for every amount. Corrupt bytes
 * surface the load error; an empty ledger renders §11.6's copy; neither
 * pretends to be the other.
 *
 * D-143 (the twin of iOS D-142): it reads core's projection, so every row
 * has the expense's stable id, edits show as edited and deleted expenses
 * are gone. A tap opens the expense (`onOpen`); a long press offers Edit and
 * Delete in place (the founder's choice, 2026-09-19). A delete is confirmed
 * first, and Undo is offered on the done toast (D-248: [ExpenseUndo], as from
 * every detail page; the foot row went).
 */
@OptIn(ExperimentalFoundationApi::class)
/** D-181: the Expenses tab's words; iOS's twin is `ExpenseCopy`. */
object ExpenseCopy {
    const val ALL_GROUPS = "All groups"
    const val PERSONAL = "Personal"
    const val NOT_INVOLVED = "not involved"
    fun effect(minor: Long, formatted: String) =
        if (minor > 0) "you lent $formatted" else if (minor < 0) "you owe $formatted" else NOT_INVOLVED
    fun paidBy(who: String, formatted: String) = "$who paid $formatted"
}

/**
 * D-181: the Expenses tab, grouped by group (the founder, 2026-09-29). A
 * section per group, the group with the newest expense first; within a
 * section, newest first; expenses in no group last, as "Personal". iOS's
 * twin: `ExpenseSections` in ExpenseListView.swift.
 */
object ExpenseSections {
    data class Section(val groupId: String?, val expenses: List<ProjectedExpenseOut>)

    fun build(expenses: List<ProjectedExpenseOut>, only: String?): List<Section> {
        val sorted = ExpenseListUi.sortedProjected(expenses).filter { only == null || it.expense.group == only }
        val order = sorted.map { it.expense.group }.distinct().sortedBy { if (it == null) 1 else 0 }
        return order.map { g -> Section(g, sorted.filter { it.expense.group == g }) }
    }
}

@Composable
fun ExpenseListScreen(
    vaultPath: String,
    onOpen: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    /** D-217: the account glyph, the bar's trailing item (null: none). */
    onOpenAccount: (() -> Unit)? = null,
    /** D-225 T7 (A5): the tab's place, kept by the shell across a pushed route or another tab. */
    keep: TabKeep = remember { TabKeep() },
) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    // Every projected expense (deleted ones are filtered below). D-225 T7 (A5): the read is the kept one, so the list is there on the first frame back.
    var projected by keep.slot<List<ProjectedExpenseOut>?>("projected") { null }
    var loadError by keep.slot<String?>("loadError") { null }
    var actionError by remember { mutableStateOf<String?>(null) }
    // D-136: payer names come from the groups the records belong to.
    var groups by keep.slot<List<NamedGroupWire>>("groups") { emptyList() }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<ProjectedExpenseOut?>(null) }
    var reloads by remember { mutableStateOf(0) }
    /** D-181: the chip that narrows the tab to one group (null: all). D-225 T7 (A5): kept with the tab's place, so Back
     *  from an expense returns to the same narrowed list at the same offset. */
    var only by keep.slot<String?>("only") { null }

    // D-178: SyncStatus.revision: read again when sync brings records.
    LaunchedEffect(vaultPath, reloads, SyncStatus.revision) {
        try {
            val (loadedGroups, loaded) = withContext(Dispatchers.IO) {
                val store = LedgerStore(vaultPath)
                // A migrated vault still waiting for your name lists payers as
                // "Unknown member" until the name is given on Balance; nothing
                // is written.
                val named = try {
                    store.namedGroups()
                } catch (_: NeedsYourNameException) {
                    emptyList()
                }
                // D-143: core's projection. It runs the one-time legacy
                // backfill first, so a vault from before records existed
                // gets ids to edit by.
                named to store.projectedExpenses().expenses
            }
            groups = loadedGroups
            projected = loaded
            loadError = null
        } catch (e: Exception) {
            loadError = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
        }
    }

    fun groupOf(e: ProjectedExpenseOut) = groups.firstOrNull { it.group.id == e.expense.group }

    fun write(revision: () -> uniffi.skipti_core.ExpenseRevisionIn, after: () -> Unit) {
        actionError = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    LedgerStore(vaultPath).reviseExpense(revision())
                    CloudSync.ledgerChanged(vaultPath)
                }.exceptionOrNull()
            }
            if (failure == null) {
                after()
                reloads++
            } else {
                actionError = failure.message ?: failure.toString()
            }
        }
    }

    val expenses = projected?.filter { !it.deleted }
    // D-223 follow-up (R2): the list (not a centred state) is what the scaffold shows.
    val listShown = loadError == null && !expenses.isNullOrEmpty()
    // D-181: on the shell's glow, not a second one of its own (D-180's seam).
    Column(Modifier.fillMaxSize()) {
        // D-217: the title (and the Norse ornament) is the scaffold's, so every state below keeps it, as iOS's bar does.
        Box(Modifier.weight(1f)) {
            TabRootScaffold(
                title = "Expenses",
                ornament = true,
                scroll = rememberShellScrollState(keep.scroll),
                iosPadding = BarDecisions.TAB_ROOT_ROOMY_PADDING,
                // D-223 follow-up (R2): with rows, Norse hands over from the ornament with iOS's list spacing (12).
                iosOrnamentSpacing = if (listShown) ExpenseListUi.SPACING else BarDecisions.CONTENT_SPACING,
                trailing = accountGlyph(dark, onOpenAccount),
            ) {
                when {
                    // D-223 (L-D217-10): the drawn empty state, for the load error too (never an empty list).
                    loadError != null -> Box(
                        Modifier.fillRemainingHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(LedgerCopy.LOAD_ERROR, loadError!!, dark)
                    }

                    expenses == null -> Box(
                        Modifier.fillRemainingHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("…", style = Type.bodyDim(dark))
                    }

                    // D-223 A2: SPEC 11.6's empty-state line, as the drawn state's title and detail.
                    expenses.isEmpty() -> Box(
                        Modifier.fillRemainingHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(EmptyCopy.EXPENSES_TITLE, EmptyCopy.EXPENSES_DETAIL, dark)
                    }

                    else -> {
                        // The default tree resolves §8.1 ids to labels; computed once.
                        val tree = remember { ffiCategoryTreeDefault() }
                        Column(verticalArrangement = Arrangement.spacedBy(ExpenseListUi.SPACING.dp)) {
                            // D-181: All groups, then each group that has expenses.
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                (listOf<Pair<String?, String>>(null to ExpenseCopy.ALL_GROUPS) +
                                    groups.filter { g -> expenses.any { it.expense.group == g.group.id } }
                                        .map { it.group.id to it.group.name }).forEach { (id, title) ->
                                    val on = only == id
                                    // D-220 (L19): the filter pill, on the page ground (outlined in Vardr Labs, masking the grid).
                                    val tone = if (on) PillTone.SELECTED else PillTone.UNSELECTED
                                    Text(
                                        title,
                                        style = Type.label(dark, 14).copy(color = ThemePills.text(tone, dark)),
                                        modifier = Modifier
                                            .themePill(tone, dark, onPage = true)
                                            .selectable(selected = on, onClick = { only = id })
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            actionError?.let {
                                Text(it, style = Type.bodyDim(dark).copy(color = Tok.owed(dark)))
                            }
                            ExpenseSections.build(expenses, only).forEach { section ->
                                val named = groups.firstOrNull { it.group.id == section.groupId }
                                Row(
                                    Modifier
                                        .padding(top = 6.dp)
                                        .semantics(mergeDescendants = true) { heading() },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    named?.let {
                                        GroupTile(it.group, dark, 30)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Text(named?.group?.name ?: ExpenseCopy.PERSONAL, style = Type.name(dark, 17), modifier = Modifier.weight(1f))
                                    Text(ExpenseEditingUi.countLabel(section.expenses.size), style = Type.bodyDim(dark).copy(fontSize = TypeScale.groupCount.sp))
                                }
                                // D-234 (L12-d): iOS's 14 inside the group's card.
                                Column(Modifier.glassCard(dark, padding = 14)) {
                                    section.expenses.forEachIndexed { index, expense ->
                                        if (index > 0) HorizontalDivider(color = Tok.hairline(dark))
                                        Box {
                                            ExpenseRow(
                                                expense = expense,
                                                payerName = ExpenseListUi.memberLabel(
                                                    expense.expense.payer, expense.expense.group, groups,
                                                ),
                                                viewer = groupOf(expense)?.selfId,
                                                tree = tree,
                                                dark = dark,
                                                canEdit = groupOf(expense) != null,
                                                onOpen = { onOpen(expense.id) },
                                                onMenu = { menuFor = expense.id },
                                                onEdit = { onEdit(expense.id) },
                                                onDelete = { confirming = expense },
                                            )
                                            DropdownMenu(
                                                expanded = menuFor == expense.id,
                                                onDismissRequest = { menuFor = null },
                                                // The theme leaves Material's lavender menu
                                                // surface in place (seen on API 34); the
                                                // menu takes the page's own raised surface.
                                                containerColor = Tok.surfaceRaised(dark),
                                            ) {
                                                SkiptiWindowLocals {
                                                    DropdownMenuItem(
                                                        text = { Text("Edit", style = Type.body(dark)) },
                                                        enabled = groupOf(expense) != null,
                                                        onClick = {
                                                            menuFor = null
                                                            onEdit(expense.id)
                                                        },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("Delete", style = Type.body(dark).copy(color = Tok.owed(dark))) },
                                                        onClick = {
                                                            menuFor = null
                                                            confirming = expense
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // D-224 (Design 3): the delete's question warns as it opens.
    HapticWarning(confirming != null)
    confirming?.let { e ->
        SkiptiAlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete this expense?") },
            text = { Text("It leaves your balances. You can undo it right after.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    write({ ExpenseEditingUi.delete(e, groupOf(e)?.selfId) }) {
                        // D-248 (U30.4): the Undo is the toast, as from every detail page.
                        ExpenseUndo.offer(e, vaultPath, groupOf(e)?.selfId)
                    }
                }) { Text("Delete expense", color = Tok.owed(dark)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpenseRow(
    expense: ProjectedExpenseOut,
    payerName: String,
    viewer: String?,
    tree: CategoryTree,
    dark: Boolean,
    canEdit: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val money = expense.expense
    val locale = AppLocale.current
    val amount = BalanceUi.moneyFormat(money.paidMinor, money.currencyCode, locale)
    val spoken = BalanceUi.moneySpoken(money.paidMinor, money.currencyCode, locale)
    val categoryLine = ExpenseListUi.categoryLabel(tree, expense.category)
    val conflict = expense.moneyConflict.isNotEmpty()
    // D-181: what it does to you (core's your_effect).
    val effect = viewer?.let { runCatching { uniffi.skipti_core.ffiYourEffect(money, it) }.getOrNull() }
    val effectText = effect?.let {
        ExpenseCopy.effect(it.amountMinor, BalanceUi.moneyFormat(kotlin.math.abs(it.amountMinor), it.currencyCode, locale))
    }
    val effectColour = when {
        (effect?.amountMinor ?: 0L) > 0 -> Tok.owing(dark)
        (effect?.amountMinor ?: 0L) < 0 -> Tok.owed(dark)
        else -> Tok.textDim(dark)
    }
    val title = expense.note?.takeIf { it.isNotBlank() } ?: categoryLine ?: "Expense"

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(vertical = 12.dp)
            .clearAndSetSemantics {
                contentDescription = ExpenseListUi.rowLabel(expense.note, payerName, spoken) +
                    (effectText?.let { " $it." } ?: "") +
                    if (conflict) " Devices entered different versions; choose one." else ""
                onClick(label = "Open the expense") { onOpen(); true }
                // TalkBack has no long press to discover: the menu's two
                // actions are offered as its own actions.
                customActions = listOfNotNull(
                    if (canEdit) CustomAccessibilityAction("Edit") { onEdit(); true } else null,
                    CustomAccessibilityAction("Delete") { onDelete(); true },
                )
            },
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title.withRunes(), style = Type.name(dark, 15), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ExpenseCopy.paidBy(payerName, amount), style = Type.bodyDim(dark).copy(fontSize = 13.sp))
            val when_ = expense.timestampMs?.let { ExpenseListUi.dateString(it.toLong(), locale) }
            val detail = listOfNotNull(when_, categoryLine?.takeIf { expense.note?.isNotBlank() == true })
            if (detail.isNotEmpty()) {
                Text(Separator.annotated(detail), style = Type.bodyDim(dark).copy(fontSize = 12.sp, color = Tok.textFaint(dark)))
            }
            // D-143: SPEC §6.1's open question, flagged where it is seen.
            if (conflict) {
                Text(Type.kicker("Choose a version"), style = Type.microLabel(dark).copy(color = Tok.owed(dark)))
            }
        }
        Spacer(Modifier.width(8.dp))
        effectText?.let {
            Text(it, style = Type.numericColored(14, effectColour), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}
