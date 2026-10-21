package com.vardrlabs.skipti

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ProjectedExpenseOut

/**
 * D-189 (the founder, 2026-09-30: "a notification system for important
 * stuff" and "that bell icon ... inside the app"): what other people added,
 * changed or deleted in your groups, worked out on this phone from the
 * records it already holds, newest first. Nothing is sent anywhere to make
 * it. Each expense appears once, for its latest change, and only when
 * someone else made that change. iOS's twin is ActivityFeed.swift.
 */
object ActivityFeed {
    enum class Kind { ADDED, CHANGED, DELETED }

    data class Item(
        val expenseId: String,
        val group: String,
        val kind: Kind,
        val by: String,
        val atMs: Long,
        val title: String,
        val amountMinor: Long,
        val currency: String,
    )

    const val LIMIT = 50
    const val TITLE = "Activity"
    const val EMPTY = "Nothing new yet. What others add, change or delete in your groups shows here."
    /** D-196: the list on a group's page. */
    const val GROUP_TITLE = "Recent activity"
    const val GROUP_EMPTY = "Nothing new yet. What others add, change or delete in this group shows here."
    const val SOMEONE = "Someone"
    const val AN_EXPENSE = "an expense"
    const val SEEN_KEY = "activity/seen-up-to"

    /** D-234 (L8): who an expense row names: the group's current member, else the group's history name (a removed
     *  person keeps theirs, [historyNames]), else "Someone". Activity, a group's Recent activity and Home's Lately all
     *  say it so. The twin of iOS's `ActivityFeed.who`. */
    fun who(item: Item, groups: Map<String, NamedGroupWire>, names: Map<String, Map<String, String>>): String =
        groups[item.group]?.members?.firstOrNull { it.id == item.by }?.name ?: names[item.group]?.get(item.by) ?: SOMEONE

    /** The latest change to each group expense, when someone other than
     *  `me` (your id and its aliases) made it; D-196: in one group only, when
     *  [only] names it. */
    fun items(expenses: List<ProjectedExpenseOut>, me: Set<String>, only: String? = null): List<Item> {
        val out = mutableListOf<Item>()
        for (e in expenses) {
            val group = e.expense.group ?: continue
            if (only != null && only != group) continue
            val edited = e.lastEditedAtMs
            val editor = e.lastEditedBy
            val author = e.addedBy
            val (kind, by, at) = when {
                edited != null && editor != null -> Triple(if (e.deleted) Kind.DELETED else Kind.CHANGED, editor, edited.toLong())
                author != null -> Triple(Kind.ADDED, author, e.addedAtMs.toLong())
                else -> continue
            }
            if (by in me) continue
            val note = e.note?.trim().orEmpty()
            out += Item(
                e.id, group, kind, by, at,
                if (note.isEmpty()) AN_EXPENSE else note,
                e.expense.paidMinor, e.expense.currencyCode,
            )
        }
        return out.sortedWith(compareByDescending<Item> { it.atMs }.thenByDescending { it.expenseId }).take(LIMIT)
    }

    /** D-210: a removed person's balance moved onto others, as the group's
     *  history shows it (one line per redistribution record). */
    data class Moved(
        val id: String,
        val group: String,
        val atMs: Long,
        val from: String,
        val currency: String,
        /** What moved, as a magnitude: the shares' sum. */
        val amountMinor: Long,
        /** Who took it, by participant id, in the record's order. */
        val to: List<String>,
    )

    /** The group's redistribution records (type 6) as history lines; a record
     *  core cannot read is skipped. D-210: in one group only when [only] names it. */
    fun moved(records: List<uniffi.skipti_core.RecordWire>, only: String? = null): List<Moved> =
        records.mapNotNull { record ->
            if (record.recordType != LedgerStore.REDISTRIBUTION_TYPE) return@mapNotNull null
            val group = record.groupId ?: return@mapNotNull null
            if (only != null && only != group) return@mapNotNull null
            val r = try {
                uniffi.skipti_core.ffiDecodeRedistribution(record.blob)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            Moved(
                id = "moved-" + CloudRecords.documentId(record), group = group, atMs = record.hlcMillis.toLong(),
                from = r.from, currency = r.currencyCode, amountMinor = Math.abs(r.shares.sumOf { it.amountMinor }),
                to = r.shares.map { it.id },
            )
        }

    /** "Sam's ₹1,000.00 moved to Prithvi, Rahul and Asha", names from the
     *  group's history (`LedgerStore.historyNames`: removed people too); null
     *  when core cannot format the amount (the line is not shown). */
    fun movedLine(m: Moved, names: Map<String, String>): String? {
        val amount = RolesMoney.format(m.amountMinor, m.currency) ?: return null
        return RolesCopy.moved(names[m.from] ?: LedgerStore.UNKNOWN_NAME, amount, m.to.mapNotNull { names[it] })
    }

    /** What the list shows, newest first: expenses others changed, balances moved. */
    data class Entry(val id: String, val atMs: Long, val item: Item? = null, val moved: Moved? = null)

    fun entries(items: List<Item>, moved: List<Moved>): List<Entry> =
        (items.map { Entry(it.expenseId, it.atMs, item = it) } + moved.map { Entry(it.id, it.atMs, moved = it) })
            .sortedWith(compareByDescending<Entry> { it.atMs }.thenByDescending { it.id })
            .take(LIMIT)

    /** D-223: what the Activity screens and Home's Lately read, in one shared read (one vault, no network).
     *  [names]: each moved balance's group history names (removed people too), by group: who a moved row is about
     *  (iOS's twin is `Loaded.names`). */
    data class Loaded(
        val items: List<Item>,
        val moved: List<Moved>,
        val movedLines: Map<String, String>,
        val groups: Map<String, NamedGroupWire>,
        val names: Map<String, Map<String, String>> = emptyMap(),
    ) {
        /** What the lists show, newest first (iOS's twin is `Loaded.entries`). */
        val entries: List<Entry> get() = entries(items, moved)
    }

    /** D-223: the read the Activity screen made, shared with Home's Lately (the twin of iOS's `ActivityFeed.load`):
     *  expenses others changed, balances moved off a removed person with their words (only lines that can be said are
     *  kept), and the groups by id; in one group when [group] names it. It never marks the feed seen: the Activity
     *  screen does that itself, for the whole ledger only. A vault read that fails is thrown, never an empty feed
     *  (CLAUDE.md 10.1: Home is the launch path); a record core cannot decode is still skipped ([moved]). Call it off
     *  the main thread. */
    fun load(store: LedgerStore, group: String?): Loaded {
        val items = store.activity(group)
        val all = moved(store.allRecords(), group)
        val namesByGroup = linkedMapOf<String, Map<String, String>>()
        val lines = linkedMapOf<String, String>()
        // D-234 (L8): every group the feed names an author in, not only those with a moved balance.
        for (g in items.map { it.group }.toSet()) namesByGroup.getOrPut(g) { store.historyNames(g) }
        for (m in all) {
            val names = namesByGroup.getOrPut(m.group) { store.historyNames(m.group) }
            movedLine(m, names)?.let { lines[m.id] = it }
        }
        val groups = store.namedGroups().associateBy { it.group.id }
        return Loaded(items, all.filter { it.id in lines }, lines, groups, namesByGroup)
    }

    /** How many are newer than what was on screen when you last looked. */
    fun unread(items: List<Item>, seenUpTo: Long): Int = items.count { it.atMs > seenUpTo }

    fun line(kind: Kind, who: String, title: String): String = when (kind) {
        Kind.ADDED -> "$who added $title"
        Kind.CHANGED -> "$who changed $title"
        Kind.DELETED -> "$who deleted $title"
    }

    fun spokenBell(unread: Int): String = if (unread == 0) TITLE else "$TITLE, $unread new"
}

/** D-189: the list the bell opens. Opening it marks everything on it seen.
 *  D-196: the bell went with the relay (D-192); a group's page opens it for
 *  that group as "Recent activity". */
@Composable
fun ActivityScreen(
    vaultPath: String,
    dark: Boolean,
    group: String? = null,
    onOpenExpense: (String) -> Unit,
    onBack: () -> Unit,
) {
    // D-223: the shared read (Home's Lately reads the same); a failed vault read is said, never an empty list.
    var feed by remember { mutableStateOf<ActivityFeed.Loaded?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(vaultPath, SyncStatus.revision) {
        withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                val read = ActivityFeed.load(store, group)
                val newest = read.items.firstOrNull()?.atMs
                if (group == null && newest != null && newest > store.activitySeenUpTo()) {
                    runCatching { store.setActivitySeenUpTo(newest) }
                }
                read
            }
        }.fold(
            onSuccess = {
                feed = it
                failed = null
                loaded = true
            },
            // The last good list stays under the line that says the read failed (iOS's twin).
            onFailure = { failed = it.message ?: it.toString() },
        )
    }
    // D-196: on the Aurora glow, as on iOS (D-189's screen was flat). D-217: the title was a 40 sp text; it is the bar's.
    PushedPage(if (group == null) ActivityFeed.TITLE else ActivityFeed.GROUP_TITLE, dark, onBack, spacing = 12.dp) {
        val entries = feed?.entries.orEmpty()
        val groups = feed?.groups.orEmpty()
        val names = feed?.names.orEmpty()
        val movedLines = feed?.movedLines.orEmpty()
        val now = System.currentTimeMillis()
        if (failed != null) {
            Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(LedgerCopy.LOAD_ERROR, style = Type.body(dark).copy(color = Tok.owed(dark)))
                Text(failed!!, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            }
        } else if (loaded && entries.isEmpty()) {
            Text(
                if (group == null) ActivityFeed.EMPTY else ActivityFeed.GROUP_EMPTY,
                style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark),
            )
        }
        if (entries.isNotEmpty()) {
            // D-234 wave (decision 102): iOS pads this card 14.
            Column(Modifier.glassCard(dark, padding = 14)) {
                entries.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(color = Tok.hairline(dark))
                    entry.item?.let { ActivityRow(it, groups[it.group], ActivityFeed.who(it, groups, names), now, dark, onOpenExpense) }
                    entry.moved?.let { MovedRow(it, movedLines[it.id] ?: "", groups[it.group], now, dark) }
                }
            }
        }
    }
}

/** D-210: one removed person's balance moved onto others, as the group's history shows it. */
@Composable
private fun MovedRow(m: ActivityFeed.Moved, line: String, named: NamedGroupWire?, nowMs: Long, dark: Boolean) {
    // D-223 (Design 6): one rule on both phones, not the system's relative words.
    val whenText = RelativeWhen.words(m.atMs, nowMs)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        if (named != null) {
            GroupTile(named.group, dark, 36)
            Spacer(Modifier.width(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(line, style = Type.label(dark, 15))
            // D-217 look fix 2: iOS draws the meta line at 13 (TypeScale.groupCount, the table's 13).
            Text(Separator.annotated(listOfNotNull(named?.group?.name, whenText).filter { it.isNotEmpty() }), style = Type.bodyDim(dark).copy(fontSize = TypeScale.groupCount.sp))
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityFeed.Item, named: NamedGroupWire?, who: String, nowMs: Long, dark: Boolean, onOpen: (String) -> Unit) {
    val whenText = RelativeWhen.words(item.atMs, nowMs)
    val amount = RolesMoney.format(item.amountMinor, item.currency) ?: ""
    val openable = item.kind != ActivityFeed.Kind.DELETED && named != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable { onOpen(item.expenseId) } else Modifier)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        if (named != null) {
            GroupTile(named.group, dark, 36)
            Spacer(Modifier.width(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                ActivityFeed.line(item.kind, who, item.title),
                style = Type.label(dark, 15).let {
                    if (item.kind == ActivityFeed.Kind.DELETED) it.copy(color = Tok.textDim(dark)) else it
                },
            )
            Text(
                Separator.annotated(listOfNotNull(named?.group?.name, amount, whenText).filter { it.isNotEmpty() }),
                style = Type.bodyDim(dark).copy(fontSize = TypeScale.groupCount.sp),
            )
        }
    }
}
