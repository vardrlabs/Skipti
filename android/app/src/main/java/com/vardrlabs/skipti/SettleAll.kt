package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.ffiBalanceLine
import kotlin.math.abs

/** D-182: Settle up's words, one place; iOS's twin is `SettleAllCopy`. The
 *  legal sentence (§11.6) stays verbatim. */
object SettleAllCopy {
    const val TITLE = "Settle up"
    const val INTRO = "The fewest payments that clear everything."
    const val EXPLAINER = SettleUi.EXPLAINER
    const val NOTHING = "Nothing to settle yet. Add an expense with the + below."
    /** D-234 (L7, decision 87): nothing to settle because every group here is a read-only copy. */
    const val NOTHING_HERE = "Nothing to settle from this phone. Your groups here can't take new records."

    /** The empty line: no groups at all, or only groups this phone can't record in (iOS's `SettleAllCopy.empty`). */
    fun empty(groupCount: Int): String = if (groupCount == 0) NOTHING else NOTHING_HERE
    const val RECORDING = "Recording…"
    const val FASTEST = "Fastest plan"
    fun sentence(youOwe: Boolean, other: String) = if (youOwe) "You pay $other" else "$other pays you"
    fun action(youOwe: Boolean, other: String) = if (youOwe) "I paid $other" else "Mark as paid"
    fun settled(group: String) = "$group is all settled"
    const val SETTLED_DETAIL = "Nobody owes anybody. Nice."
    fun recorded(other: String) = "Settled with $other. Recorded in your ledger. No money moved."
}

/** D-182: one group's part of Settle up, framed by that group's own self. */
data class SettleSection(val named: NamedGroupWire, val lines: List<BalanceLineOut>) {
    /** Nobody in the group owes you, and you owe nobody in it. */
    val settled: Boolean get() = lines.all { it.entries.isEmpty() }

    /** D-221 fix wave (S1): each card's payment, as the latch holds it. */
    val keys: List<SettleKey> get() = lines.flatMap { line -> line.entries.map { SettleKey(named.group.id, line.currencyCode, it) } }
}

/** D-221 fix wave (S1): one Settle up card's payment (its group, currency and entry, amount included). */
data class SettleKey(val group: String, val currency: String, val entry: ShareOut)

object SettleAll {
    /** Every group, each with core's lines for this device's self in it.
     *  The same read the screen makes; iOS's twin is `SettleAll.sections`. */
    fun sections(store: LedgerStore): List<SettleSection> {
        // D-210: a read-only copy has nothing to settle in (nothing can be recorded in it).
        val copies = store.readOnlyChecked().keys
        val named = store.namedGroups().filter { it.group.id !in copies }
        val expenses = store.loadExpenses().map { it.toInput() }
        val settlements = store.loadSettlements().map { it.toInput() }
        val redistributions = store.loadRedistributions()
        val removed = store.removedPeople()
        return named.map {
            SettleSection(it, ffiBalanceLine(expenses, settlements, redistributions, removed[it.group.id].orEmpty().map { m -> m.id }, it.selfId, it.group.id))
        }
    }

    /** D-221 (Q17 a): the one way a payment is recorded, Settle up's card and the group page's next card alike:
     *  §5.8's settlement in the ledger, then sync, fire and forget (§10.3: saved locally first). Blocking: call it off
     *  the main thread. The twin of iOS's `SettleAll.record`. D-224 fix wave (F7): when [askSettled] (the group page's
     *  card), it also says whether the group is all settled now, read in the same store before the toast is posted, so
     *  the page starts its celebration at once; false otherwise, or when that read fails. */
    fun record(vaultPath: String, entry: ShareOut, currency: String, named: NamedGroupWire, askSettled: Boolean = false): Boolean {
        val store = LedgerStore(vaultPath)
        append(store, entry, currency, named)
        CloudSync.ledgerChanged(vaultPath)
        val settled = askSettled && runCatching { GroupProgress.allSettled(store, named.group.id) }.getOrDefault(false)
        // D-224 (Design 7): the one post for both cards, "Settled with" the person (a removed one by the name the
        // history keeps) and core's amount; on the main thread, where the moment's haptic plays. Settle up keeps its own
        // green line (section 11.6's sentence); the toast is what shows when that line is scrolled away.
        val name = LedgerStore.label(entry.id, named, runCatching { store.historyNames(named.group.id) }.getOrDefault(emptyMap()))
        val amount = DoneMoments.amount(abs(entry.minorUnits), currency)
        MainThread.run { DoneCenter.post(DoneMoments.line(DoneCopy.settledWith(name), amount)) }
        return settled
    }

    /** The record itself: a settlement filed under its group, framed by the group's own self. */
    fun append(store: LedgerStore, entry: ShareOut, currency: String, named: NamedGroupWire) =
        store.appendSettlement(SettleUi.settlementFor(entry, currency, named.group.id, named.selfId))
}

/**
 * D-182: Settle up across every group (the founder's canvas): a section per
 * group, a card per person you owe or who owes you, and "all settled" for a
 * group with nothing left. Recording is §5.8's path, unchanged: a settlement
 * record in the ledger (SettleUi.settlementFor), never a flag, never money.
 * `SettleScreen` stays for the `--settle` proof.
 */
@Composable
fun SettleAllScreen(
    vaultPath: String,
    dark: Boolean,
    onOpenAccount: (() -> Unit)? = null,
    /** D-225 T7 (A5): the tab's place, kept by the shell across a pushed route or another tab. */
    keep: TabKeep = remember { TabKeep() },
) {
    val scope = rememberCoroutineScope()
    var sections by keep.slot<List<SettleSection>>("sections") { emptyList() }
    // D-221 fix wave (S1): a card stays "Recording…" from its tap until a reload no longer shows that payment, so a
    // second tap in the reload's window records nothing; only a failure lets go. One at a time: while one records, every
    // other card's Settle reads as disabled.
    val latch = remember { SettleLatch<SettleKey>() }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var error by keep.slot<String?>("error") { null }
    var loaded by keep.slot("loaded") { false }
    // D-234 (L7): every group, read-only copies too (Settle up lists only the ones that can be recorded in).
    var groupCount by keep.slot("groupCount") { 0 }
    var reloads by remember { mutableIntStateOf(0) }

    LaunchedEffect(vaultPath, reloads, SyncStatus.revision) {
        withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                SettleAll.sections(store) to store.namedGroups().size
            }
        }.fold(
            onSuccess = { (found, total) ->
                sections = found
                groupCount = total
                latch.moved(found.flatMap { s -> s.keys })
                error = null
            },
            onFailure = {
                error = if (it is NeedsYourNameException) "Add your name on Balance first."
                else "Could not load the ledger. ${it.message ?: it}"
            },
        )
        loaded = true
    }

    fun record(entry: ShareOut, currency: String, named: NamedGroupWire, key: SettleKey) {
        if (!latch.begin(key)) return
        val other = LedgerStore.label(entry.id, named)
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { SettleAll.record(vaultPath, entry, currency, named) }.exceptionOrNull()
            }
            if (failure == null) {
                confirmation = SettleAllCopy.recorded(other)
                reloads++
                SyncStatus.changed()
            } else {
                latch.failed()
                error = "Could not record the settlement. ${failure.message ?: failure}"
            }
        }
    }

    TabRootScaffold(
        title = SettleAllCopy.TITLE,
        ornament = true,
        scroll = rememberShellScrollState(keep.scroll),
        iosPadding = BarDecisions.TAB_ROOT_ROOMY_PADDING,
        trailing = accountGlyph(dark, onOpenAccount),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SettleAllCopy.INTRO, style = Type.bodyDim(dark).copy(fontSize = TypeScale.settleIntro.sp))
            // §11.6, verbatim: the sentence that keeps SKIPTI a notebook.
            Text(SettleAllCopy.EXPLAINER, style = Type.bodyDim(dark).copy(fontSize = TypeScale.settleIntro.sp))
        }
        confirmation?.let { Text(it, style = Type.body(dark).copy(color = Tok.owing(dark))) }
        error?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark))) }
        if (loaded && sections.isEmpty() && error == null) {
            Text(SettleAllCopy.empty(groupCount), style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark))
        }
        sections.forEach { section ->
            val named = section.named
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupTile(named.group, dark, 30)
                Spacer(Modifier.width(10.dp))
                Text(named.group.name, style = Type.name(dark, 17))
            }
            if (section.settled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Tok.owingSoft(dark), ThemeArt.panelShape(Appearance.theme, 18))
                        .padding(14.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TabIcons.CircleCheck, contentDescription = null, tint = Tok.owing(dark), modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(SettleAllCopy.settled(named.group.name), style = Type.label(dark, 15).copy(color = Tok.owing(dark)))
                        Text(SettleAllCopy.SETTLED_DETAIL, style = Type.bodyDim(dark).copy(fontSize = 13.sp))
                    }
                }
            } else {
                section.lines.flatMap { line -> line.entries.map { line.currencyCode to it } }.forEach { (currency, entry) ->
                    val youOwe = entry.minorUnits < 0
                    val other = LedgerStore.label(entry.id, named)
                    val amount = abs(entry.minorUnits)
                    val formatted = BalanceUi.moneyFormat(amount, currency, AppLocale.current)
                    val spoken = BalanceUi.moneySpoken(amount, currency, AppLocale.current)
                    val key = SettleKey(named.group.id, currency, entry)
                    Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = "${SettleAllCopy.sentence(youOwe, other)}. $spoken."
                            },
                        ) {
                            // D-213 (fix wave 2): the shared avatar, so it follows the theme's person shape.
                            PersonAvatar(other, dark, 44)
                            Spacer(Modifier.width(12.dp))
                            Text(SettleAllCopy.sentence(youOwe, other), style = Type.body(dark), modifier = Modifier.weight(1f))
                            Text(formatted, style = Type.numericColored(19, if (youOwe) Tok.owed(dark) else Tok.owing(dark)), maxLines = 1)
                        }
                        if (latch.settling(key)) {
                            Text(SettleAllCopy.RECORDING, style = Type.bodyDim(dark))
                        } else {
                            PrimaryAction(SettleAllCopy.action(youOwe, other), dark, enabled = !latch.busy) {
                                record(entry, currency, named, key)
                            }
                        }
                    }
                }
                val transfers = section.lines.flatMap { line -> line.transfers.map { line.currencyCode to it } }
                if (transfers.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel(SettleAllCopy.FASTEST, dark)
                        val names = named.members.associate { it.id to LedgerStore.label(it.id, named) }
                        transfers.forEach { (currency, t) ->
                            val row = SettleUi.planRow(t.from, t.to, named.selfId, names).replaceFirstChar { it.uppercase() }
                            Text(
                                "$row ${BalanceUi.moneyFormat(t.amountMinor, currency, AppLocale.current)}",
                                style = Type.bodyDim(dark).copy(fontSize = 14.sp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
