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

/**
 * D-143: one expense, opened from the Expenses list. The twin of iOS
 * `ExpenseDetailView` (D-142). The whole split, SPEC §5.7's "added by" and
 * "last edited by" with a timestamp, Edit (the Add form, filled in) and
 * Delete (confirmed first; the toast then offers Undo, D-248's [ExpenseUndo]). When two devices set
 * different amounts, SPEC §6.1's question is the first thing on the page,
 * with one button per version. Read from core's projection, never the kv
 * ledger.
 *
 * `named` is passed only by the `--expense-conflict` proof, whose throwaway
 * vault holds no groups; the app reads the expense's own group from the vault.
 */
@Composable
fun ExpenseDetailScreen(
    vaultPath: String,
    expenseId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: (String) -> Unit,
    named: NamedGroupWire? = null,
    // D-217 final fix: set when a tapped push opened the expense: iOS presents it as a sheet with this pill ("Close").
    closeLabel: String? = null,
) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var expense by remember { mutableStateOf<ProjectedExpenseOut?>(null) }
    var group by remember { mutableStateOf(named) }
    // The group's history names (`historyNames`): people removed from it too.
    var history by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // D-249: your ids, so a Just me expense reads "You" (it has no group to name you in).
    var yourIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    // D-235 (H1): one revision per tap: a double tap on Delete or on a version to keep writes it once.
    val writing = remember { SaveLatch() }
    var confirmingDelete by remember { mutableStateOf(false) }
    var reloads by remember { mutableStateOf(0) }
    // D-210: in a read-only copy an expense is only read; nothing to edit or
    // delete. An unreadable mark counts as a copy: nothing is offered that
    // could not be undone.
    var isCopy by remember { mutableStateOf(false) }

    // Look fix F3: read again whenever sync brings a change (an edit on another phone), as the list does.
    LaunchedEffect(vaultPath, expenseId, reloads, SyncStatus.revision) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                val found = store.projectedExpenses().expenses
                    .firstOrNull { it.id == expenseId && !it.deleted }
                val own = named ?: found?.let { e ->
                    runCatching { store.namedGroups() }.getOrDefault(emptyList())
                        .firstOrNull { it.group.id == e.expense.group }
                }
                val groupId = found?.expense?.group
                val copy = if (groupId == null) false else runCatching { groupId in store.readOnlyChecked() }.getOrDefault(true)
                val names = groupId?.let { runCatching { store.historyNames(it) }.getOrDefault(emptyMap()) } ?: emptyMap()
                val me = runCatching { store.loadSelf() }.getOrNull()
                yourIds = listOfNotNull(me?.id).plus(me?.aliases.orEmpty()).toSet()
                Detail(found, own, copy, names)
            }
        }
        result.fold(
            onSuccess = { (found, own, copy, names) ->
                history = names
                expense = found
                group = own
                isCopy = copy
                loadError = if (found == null) "It was deleted, possibly on another device." else null
            },
            onFailure = { loadError = it.message ?: it.toString() },
        )
    }

    fun name(id: String): String {
        if (expense?.expense?.group == null && id in yourIds) return ExpenseCopy.YOU
        return group?.let { LedgerStore.label(id, it, history) } ?: history[id] ?: "Unknown member"
    }

    fun write(revision: () -> uniffi.skipti_core.ExpenseRevisionIn, after: () -> Unit) {
        if (!writing.begin()) return
        actionError = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    LedgerStore(vaultPath).reviseExpense(revision())
                    CloudSync.ledgerChanged(vaultPath)
                }.exceptionOrNull()
            }
            writing.release()
            if (failure == null) after() else actionError = failure.message ?: failure.toString()
        }
    }

    // D-217: a pushed page. The bar (the round back button and the note as the title, "Expense" when there is none,
    // as iOS) overlays the content, which starts under it and scrolls beneath.
    // D-217 look fix 4: Delete and Edit are pinned under the content as iOS's `formActionBar` pins them (not in a read-only
    // copy, and not while the expense is missing); the page's last line, the footer sentence, scrolls above them.
    val pinned = expense != null && !isCopy
    val scroll = rememberScrollState()
    // D-220 (L-D217-3): the page is its bar's backdrop (Aurora blurs it under the bar).
    val backdrop = rememberBackdrop()
    Column(Modifier.fillMaxSize().auroraBackground(dark)) {
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .backdropSource(backdrop)
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = BarDecisions.scrollTop(16f).dp, bottom = if (pinned) 8.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val e = expense
                if (e == null) {
                    Text("This expense is not in your ledger", style = Type.body(dark))
                    loadError?.let { Text(it, style = Type.bodyDim(dark)) }
                    return@Column
                }

                ExpenseEditingUi.conflictPrompt(e, AppLocale.current)?.let { prompt ->
                    ConflictCard(e, prompt, ::name, dark) { option ->
                        write({ ExpenseEditingUi.keep(option, e, group?.selfId) }) { reloads++ }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // D-217 (look defect 9): the category, as iOS draws it; none when the expense has none.
                    ExpenseDetailUi.kicker(CategoryLevels.tree, e.category)?.let {
                        Text(Type.kicker(it), style = Type.microLabel(dark).copy(color = Tok.groundFaint(dark)))
                    }
                    Text(
                        ExpenseEditingUi.amount(e.expense, AppLocale.current),
                        style = Type.numeric(34, dark = dark),
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = BalanceUi.moneySpoken(e.expense.paidMinor, e.expense.currencyCode, AppLocale.current)
                        },
                    )
                    Row {
                        // D-249: a Just me expense names its ledger, not a payer (it is always you).
                        Text(
                            Type.kicker(if (e.expense.group == null) SpendingCopy.JUST_ME else "Paid by ${name(e.expense.payer)}"),
                            style = Type.microLabel(dark).copy(color = Tok.groundFaint(dark)),
                        )
                        Spacer(Modifier.weight(1f))
                        e.timestampMs?.let {
                            Text(
                                ExpenseListUi.dateString(it.toLong(), AppLocale.current),
                                style = Type.numericColored(12, Tok.groundFaint(dark), weight = FontWeight.Normal),
                            )
                        }
                    }
                }

                // D-249: Just me has nothing to split.
                if (e.expense.group == null) {
                    Text(Type.kicker(SpendingCopy.ONLY_YOU_NOTHING_TO_SPLIT), style = Type.microLabel(dark).copy(color = Tok.groundFaint(dark)))
                } else Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Tok.surface(dark), ThemeArt.panelShape(Appearance.theme, 12))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionLabel("Split", dark)
                    e.expense.shares.forEach { share ->
                        Row(
                            Modifier.clearAndSetSemantics {
                                contentDescription = "${name(share.id)}, " +
                                    BalanceUi.moneySpoken(share.amountMinor, e.expense.currencyCode, AppLocale.current)
                            },
                        ) {
                            Text(name(share.id), style = Type.body(dark))
                            Spacer(Modifier.weight(1f))
                            Text(
                                BalanceUi.moneyFormat(share.amountMinor, e.expense.currencyCode, AppLocale.current),
                                style = Type.numeric(15, FontWeight.Normal, dark),
                            )
                        }
                    }
                }

                // D-225 fix wave (I2, A10): the stamps' time in the phone's own 12 or 24-hour setting, as iOS's.
                val context = androidx.compose.ui.platform.LocalContext.current
                val hours24 = android.text.format.DateFormat.is24HourFormat(context)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Only a creation that names its author says who added it: a
                    // pre-D-141 expense's record was minted by the one-time backfill,
                    // so its time would be the backfill's, not the add's.
                    e.addedBy?.let {
                        Text(Separator.annotated(listOf("Added by ${name(it)}", ExpenseDetailUi.stamp(e.addedAtMs.toLong(), AppLocale.current, hours24))), style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
                    }
                    e.lastEditedAtMs?.let { at ->
                        val who = e.lastEditedBy?.let { "Last edited by ${name(it)}" } ?: "Last edited"
                        Text(Separator.annotated(listOf(who, ExpenseDetailUi.stamp(at.toLong(), AppLocale.current, hours24))), style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
                    }
                }

                actionError?.let {
                    Text(it, style = Type.bodyDim(dark).copy(color = Tok.owed(dark)))
                }

                Text(Type.kicker("Recorded in your ledger. It does not move money."), style = Type.microLabel(dark).copy(color = Tok.groundFaint(dark)))
            }
            if (closeLabel != null) SheetBar(ExpenseDetailUi.title(expense?.note), onCancel = onBack, scroll = scroll, cancelLabel = closeLabel, backdrop = backdrop)
            else PushedBar(title = ExpenseDetailUi.title(expense?.note), onBack = onBack, scroll = scroll, backdrop = backdrop)
        }
        if (pinned) FormActionBar {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // D-213 (fix wave 2): iOS's SecondaryButtonStyle (it is not a danger button there either).
                SecondaryAction("Delete", dark, modifier = Modifier.weight(1f)) { confirmingDelete = true }
                // D-249: a Just me expense edits too (it has no group by design); a group's needs its group here.
                PrimaryAction(
                    label = "Edit",
                    dark = dark,
                    enabled = ExpenseDeleteCopy.canEdit(expense?.expense?.group, group),
                    modifier = Modifier.weight(1f),
                    onClick = onEdit,
                )
            }
        }
    }

    // D-224 (Design 3): the delete's question warns as it opens.
    HapticWarning(confirmingDelete)
    if (confirmingDelete) {
        val e = expense
        SkiptiAlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this expense?") },
            // D-249: a Just me expense was never in a balance.
            text = { Text(ExpenseDeleteCopy.message(justMe = e != null && e.expense.group == null)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    if (e != null) {
                        write({ ExpenseEditingUi.delete(e, group?.selfId) }) {
                            // D-248 (U30.4): the Undo the dialog promised, here, whoever opened this page.
                            ExpenseUndo.offer(e, vaultPath, group?.selfId)
                            onDeleted(e.id)
                        }
                    }
                }) { Text("Delete expense", color = Tok.owed(dark)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** SPEC §6.1: each competing version shown whole (amount, payer, split),
 *  because two versions can share an amount and differ in who paid. */
@Composable
private fun ConflictCard(
    e: ProjectedExpenseOut,
    prompt: String,
    name: (String) -> String,
    dark: Boolean,
    onKeep: (ExpenseInput) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tok.surfaceRaised(dark), ThemeArt.panelShape(Appearance.theme, 12))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(prompt, style = Type.body(dark))
        e.moneyConflict.forEach { option ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Tok.hairline(dark), ThemeArt.panelShape(Appearance.theme, 10))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    ExpenseEditingUi.amount(option, AppLocale.current),
                    style = Type.numeric(20, dark = dark),
                    modifier = Modifier.semantics {
                        contentDescription = BalanceUi.moneySpoken(option.paidMinor, option.currencyCode, AppLocale.current)
                    },
                )
                Text(Type.kicker("Paid by ${name(option.payer)}"), style = Type.microLabel(dark))
                option.shares.forEach { share ->
                    Row {
                        Text(name(share.id), style = Type.bodyDim(dark))
                        Spacer(Modifier.weight(1f))
                        Text(
                            BalanceUi.moneyFormat(share.amountMinor, option.currencyCode, AppLocale.current),
                            style = Type.numericColored(14, Tok.textDim(dark), FontWeight.Normal),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                SecondaryAction(ExpenseEditingUi.keepLabel(option, e, AppLocale.current), dark) { onKeep(option) }
            }
        }
    }
}

/** §5.7's timestamp: day, month, year and time in the app's locale (dates
 *  are presentation and format in the shell, D-54). */
object ExpenseDetailUi {
    /** D-217 Task 6: the bar's title is the note, "Expense" when there is none (iOS: `expense?.note ?? "Expense"`). */
    fun title(note: String?): String = note ?: "Expense"

    /** D-217 Task 6 (look defect 9): the kicker over the amount is the category ("Parent · Leaf"), as iOS draws it;
     *  none when the expense has no category the tree knows (the note is the bar's title now, not a kicker). */
    fun kicker(tree: uniffi.skipti_core.CategoryTree, category: String?): String? = ExpenseListUi.categoryLabel(tree, category)

    /** D-221 fix wave (S10): [AppDate.stamp], as iOS's `AppDate.stamp`; D-225 fix wave (I2): in the phone's 12 or
     *  24-hour time. */
    fun stamp(ms: Long, locale: LocaleTag, hours24: Boolean): String = AppDate.stamp(ms, hours24, locale)

}

/** What an expense's page reads in one go: the expense, its group, whether the
 *  group is a read-only copy, and the group's history names. */
private data class Detail(
    val expense: ProjectedExpenseOut?, val group: NamedGroupWire?, val isCopy: Boolean, val names: Map<String, String>,
)

/** D-248 (U30.4): a deleted expense's Undo comes to where the delete happened: the done toast ("Expense deleted", its
 *  description) with Undo, wherever the detail was opened (the group page, Lately, Activity, a notification, the Expenses
 *  list) and for the list's long-press delete. It stays for the hold's window ([SettleHold.window]: longer with a screen
 *  reader). Undo writes the restoring revision from the projection that holds the delete, then every screen reads again.
 *  The Expenses list's foot row went (one way to undo, everywhere). The twin of iOS's `ExpenseUndo`. */
object ExpenseUndo {
    fun moment(deleted: ProjectedExpenseOut, seconds: Double): DoneMoment =
        DoneMoments.lineWithAction(DoneCopy.EXPENSE_DELETED, DoneCopy.UNDO, seconds, listOf(deleted.note))

    /** Posts the toast; its Undo writes [undo] off the main thread ([run]), and a write that fails says "Not restored."
     *  back on the main thread ([onMain]; review M2). On the main thread (the centre's rule). */
    fun offer(
        deleted: ProjectedExpenseOut,
        vaultPath: String,
        editor: String?,
        center: DoneMomentCenter = DoneCenter,
        screenReader: Boolean = ScreenReader.running(),
        store: () -> LedgerStore = { LedgerStore(vaultPath) },
        written: (String) -> Unit = SettleHold::liveWritten,
        run: (() -> Unit) -> Unit = { work -> kotlinx.coroutines.MainScope().launch(Dispatchers.IO) { work() } },
        /** When the toast is posted (uptime ms, in the app). */
        clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
        onMain: (() -> Unit) -> Unit = { work -> android.os.Handler(android.os.Looper.getMainLooper()).post(work) },
    ) {
        center.post(moment(deleted, SettleHold.window(screenReader)), clock(), onAction = {
            run {
                if (!undo(deleted, store(), editor, written)) onMain { center.post(DoneMoments.line(DoneCopy.NOT_RESTORED), clock()) }
            }
        })
    }

    /** Written from the projection that HOLDS the delete, so the Undo has seen it; then every screen reads again
     *  ([written]). False when it could not be written ([offer] then says "Not restored.", as iOS). */
    fun undo(deleted: ProjectedExpenseOut, store: LedgerStore, editor: String?, written: (String) -> Unit = SettleHold::liveWritten): Boolean =
        runCatching {
            val current = store.projectedExpenses().expenses.firstOrNull { it.id == deleted.id } ?: deleted
            store.reviseExpense(ExpenseEditingUi.undo(current, editor))
            written(store.path)
        }.isSuccess
}

/** D-249: the delete confirmation's words and whether Edit is offered, for a group's expense and a Just me one (the
 *  list's long press and the detail page say the same). iOS's twin is `ExpenseDeleteCopy`. */
object ExpenseDeleteCopy {
    const val GROUP_MESSAGE = "It leaves your balances. You can undo it right after."

    fun message(justMe: Boolean): String = if (justMe) SpendingCopy.DELETE_MESSAGE else GROUP_MESSAGE

    /** A Just me expense always; a group's only while this phone holds the group. */
    fun canEdit(group: String?, named: NamedGroupWire?): Boolean = group == null || named != null
}
