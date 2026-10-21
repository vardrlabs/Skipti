package com.vardrlabs.skipti

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.CategoryShareOut
import uniffi.skipti_core.CategoryTree
import uniffi.skipti_core.CurrencySpendingOut
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.SpendingExpenseIn
import uniffi.skipti_core.SpendingGroupIn
import uniffi.skipti_core.SpendingOut
import uniffi.skipti_core.SpendingRowOut
import uniffi.skipti_core.SpendingView
import uniffi.skipti_core.ffiSpending
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// D-249 (U10, decisions 163 to 166, 187 to 193, 205, 206): "Just me" and "All my spending", the twin of iOS's
// Spending.swift. A Just me expense is the ordinary Expense record in the personal scope (no group, you paid, shares
// [you: total]); every amount here is core's (`ffiSpending`, `ffiMoneyFormat`), and the shell only passes the phone's
// month bounds in ms. SpendingTest compares these words with the Swift file's.

/** The words of Home's Your spending section, the page, the Ledger row and the Just me pieces elsewhere. iOS's twin is
 *  `SpendingCopy`. */
object SpendingCopy {
    const val TITLE = "Your spending"
    const val JUST_ME = "Just me"
    const val ALL = "All my spending"
    const val THIS_MONTH = "This month"
    fun more(n: Int) = "+$n more"
    const val EMPTY = "Nothing yet this month"
    /** The card to TalkBack: "Your spending, Just me, four thousand ... this month". */
    fun spoken(view: String, amount: String?): String =
        if (amount == null) "$TITLE, $view, $EMPTY" else "$TITLE, $view, $amount this month"
    const val HINT = "Opens your spending"
    const val ALL_NOTE = "Your share of group expenses, plus Just me."
    fun groupMeta(group: String) = "$group, your share"
    const val PREV = "Previous month"
    const val NEXT = "Next month"
    const val SPENT = "Spent"
    const val CHART = "Chart"
    const val TABLE = "Table"
    val COLUMNS = listOf("Category", "Spent", "Share")
    const val EXPENSES = "Expenses"
    const val EMPTY_TITLE = "Nothing here yet"
    /** Decision 188: no account or sync word on a screen a no-account person sees (GUARANTEES #5). */
    const val EMPTY_DETAIL = "Things you pay for yourself go here. Nobody owes anybody."
    fun emptyMonth(month: String) = "Nothing in $month"
    const val EXPORT = "Export Just me"
    /** You > Appearance (decision 163). */
    const val HOME_ROW = "Spending on Home"
    const val HOME_SUMMARY = "This month, under your ledgers"
    /** Add expense with Just me chosen. */
    const val ONLY_YOU_NOTHING_TO_SPLIT = "Only you. Nothing to split."
    /** Decision 193: the Ledger page's meta lines. */
    const val ONLY_YOU = "Only you"
    fun since(monthYear: String) = "Since $monthYear"
    /** The Expenses tab's right column for a Just me row (was "not involved"). */
    fun spentAmount(amount: String) = "spent $amount"
    /** Expense detail's delete confirmation for a Just me expense. */
    const val DELETE_MESSAGE = "It leaves Just me. You can undo it right after."
    /** A Wallet by BudgetBakers file's two targets. */
    const val IMPORT_INTO_JUST_ME = "Import into Just me"
    const val IMPORT_AS_GROUP = "Import as a new group"
}

/** Decision 165: the hero with no group yet. iOS's twin is `NoGroupHeroCopy`. */
object NoGroupHeroCopy {
    const val KICKER = "No groups yet"
    const val TITLE = "Splitting with someone?"
    const val LINE = "Make a group for a trip, a flat or a dinner."
    const val CREATE = "Create a group"
    const val JOIN = "Join"
    const val IMPORT = "Import"
}

/** Which hero Home draws: the balance across your groups, or (no group yet) the one that asks. iOS's twin:
 *  `HomeHero.kind`. */
enum class HomeHero {
    BALANCE, NO_GROUP;

    companion object {
        fun kind(groupCount: Int): HomeHero = if (groupCount == 0) NO_GROUP else BALANCE
    }
}

val SpendingView.label: String get() = if (this == SpendingView.ALL) SpendingCopy.ALL else SpendingCopy.JUST_ME

/** Decision 189: the card's switch and "Spending on Home" are this phone's own (a preferences file of their own), never
 *  synced settings (SettingsSync's table names neither). The keys are iOS's UserDefaults keys. The live values are
 *  Compose state, read once from the file ([load]) and written by [setView] / [setOnHome]. */
object SpendingPrefs {
    const val PREFS = "skipti-spending"
    const val VIEW_KEY = "spending.view"
    const val ON_HOME_KEY = "spending.onHome"
    const val JUST_ME_RAW = "justMe"
    const val ALL_RAW = "all"

    /** Anything but "all" reads as Just me (the default). */
    fun view(raw: String?): SpendingView = if (raw == ALL_RAW) SpendingView.ALL else SpendingView.JUST_ME
    fun raw(view: SpendingView): String = if (view == SpendingView.ALL) ALL_RAW else JUST_ME_RAW
    /** On until the person turns it off. */
    fun onHome(stored: Boolean?): Boolean = stored ?: true

    fun readView(prefs: SharedPreferences): SpendingView = view(prefs.getString(VIEW_KEY, null))
    fun readOnHome(prefs: SharedPreferences): Boolean =
        onHome(if (prefs.contains(ON_HOME_KEY)) prefs.getBoolean(ON_HOME_KEY, true) else null)
    fun write(view: SpendingView, prefs: SharedPreferences) { prefs.edit().putString(VIEW_KEY, raw(view)).apply() }
    fun writeOnHome(on: Boolean, prefs: SharedPreferences) { prefs.edit().putBoolean(ON_HOME_KEY, on).apply() }

    /** Decision 206: the view the page opens in. From the Expenses tab's Just me header it is always Just me, and
     *  opening it there writes nothing (the card keeps the remembered view); from the card it is the remembered one.
     *  A tap on the page's switch is remembered as anywhere else. */
    fun pageStart(fromJustMeHeader: Boolean, stored: SpendingView): SpendingView =
        if (fromJustMeHeader) SpendingView.JUST_ME else stored

    var shownView by mutableStateOf(SpendingView.JUST_ME)
        private set
    var shownOnHome by mutableStateOf(true)
        private set
    private var loaded = false

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        shownView = readView(p)
        shownOnHome = readOnHome(p)
    }

    fun setView(context: Context, view: SpendingView) {
        write(view, prefs(context))
        shownView = view
    }

    fun setOnHome(context: Context, on: Boolean) {
        writeOnHome(on, prefs(context))
        shownOnHome = on
    }
}

/** A calendar month in the phone's own time zone (ISO, which is Gregorian), its bounds in ms for core ([from, until)),
 *  and its words. The month names are fixed English, as every other word in the app, so both phones print the same.
 *  iOS's twin is `SpendingMonth`. */
data class SpendingMonth(val year: Int, /** 1 to 12. */ val month: Int) : Comparable<SpendingMonth> {
    data class Bounds(val fromMs: ULong, val untilMs: ULong)

    fun adding(months: Int): SpendingMonth {
        val index = year * 12 + (month - 1) + months
        val y = Math.floorDiv(index, 12)
        return SpendingMonth(y, index - y * 12 + 1)
    }

    /** The first instant of the month and of the next, in ms (a month before 1970 starts at 0). */
    fun bounds(zone: ZoneId = ZoneId.systemDefault()): Bounds {
        fun start(m: SpendingMonth): ULong =
            maxOf(0L, ZonedDateTime.of(m.year, m.month, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()).toULong()
        return Bounds(start(this), start(adding(1)))
    }

    /** "October". */
    val name: String get() = NAMES[Math.floorMod(month - 1, 12)]
    /** "October 2026". */
    val label: String get() = "$name $year"

    override fun compareTo(other: SpendingMonth): Int = compareValuesBy(this, other, { it.year }, { it.month })

    companion object {
        val NAMES = listOf(
            "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
            "November", "December",
        )

        fun containing(ms: Long, zone: ZoneId = ZoneId.systemDefault()): SpendingMonth {
            val at = Instant.ofEpochMilli(ms).atZone(zone)
            return SpendingMonth(at.year, at.monthValue)
        }

        fun current(zone: ZoneId = ZoneId.systemDefault()): SpendingMonth = containing(System.currentTimeMillis(), zone)
    }
}

/** Decision 193: a ledger's meta line on the Ledger page. A group: "Since {Month Year}" from its create record's time
 *  (core's projection `created`, `millis:counter:device`) and its people; Just me: "Only you". iOS's twin. */
object LedgerMeta {
    /** The create record's ms, read from core's `created` identity; null when it does not parse. */
    fun sinceMs(created: String): Long? = created.split(":").firstOrNull()?.toLongOrNull()

    fun groupParts(sinceMs: Long?, people: Int, zone: ZoneId = ZoneId.systemDefault()): List<String> =
        listOfNotNull(sinceMs?.let { SpendingCopy.since(SpendingMonth.containing(it, zone).label) }, GroupCopy.people(people))

    val JUST_ME_PARTS = listOf(SpendingCopy.ONLY_YOU)
}

/** One read of Your spending for a month and a view: core's `ffiSpending` over the live expenses (core's projection,
 *  deletions out), and whether the view has anything in any month (the page's "Nothing here yet"). iOS's twin. */
object SpendingLoad {
    data class Loaded(
        val out: SpendingOut,
        val hasAny: Boolean,
        /** Group id to its name, for "{group}, your share". */
        val groupNames: Map<String, String>,
    )

    /** The live expenses as core's spending inputs: the row keeps the leaf, the slice counts under its parent (as the
     *  group page's `ffiGroupStats`). */
    fun inputs(live: List<ProjectedExpenseOut>, tree: CategoryTree): List<SpendingExpenseIn> =
        live.filter { !it.deleted }.map {
            SpendingExpenseIn(
                id = it.id, expense = it.expense, note = it.note, category = it.category,
                sliceCategory = CategoryLevels.parent(it.category, tree),
                timestampMs = it.timestampMs, addedAtMs = it.addedAtMs,
            )
        }

    /** Decision 205: who you are in each group (its `selfId`, as the Balance tab reads it), so All counts a group
     *  expense's share only when it is yours in THAT group. One entry per group, in order; Just me needs none. */
    fun groups(named: List<NamedGroupWire>): List<SpendingGroupIn> {
        val out = mutableListOf<SpendingGroupIn>()
        for (g in named) if (out.none { it.groupId == g.group.id }) out += SpendingGroupIn(g.group.id, g.selfId)
        return out
    }

    fun load(store: LedgerStore, month: SpendingMonth, view: SpendingView, zone: ZoneId = ZoneId.systemDefault()): Loaded {
        val live = store.projectedExpenses().expenses
        val named = try {
            store.namedGroups()
        } catch (_: NeedsYourNameException) {
            emptyList()
        }
        val inputs = inputs(live, CategoryLevels.tree)
        val yours = groups(named)
        val bounds = month.bounds(zone)
        val out = ffiSpending(inputs, yours, bounds.fromMs, bounds.untilMs, view)
        val ever = if (out.rows.isEmpty()) ffiSpending(inputs, yours, 0uL, ULong.MAX_VALUE, view).rows.isNotEmpty() else true
        return Loaded(out, ever, named.associate { it.group.id to it.group.name })
    }
}

/** Decision 193: a ledger Add expense can file under. */
sealed class LedgerChoice {
    data object JustMe : LedgerChoice()
    data class Group(val id: String) : LedgerChoice()
}

/** One row of the Ledger page. */
data class LedgerOption(val choice: LedgerChoice, val title: String, val parts: List<String>, val group: GroupWire?)

/** Decision 193: the Ledger page's options from what Add expense holds: Just me, then each writable group with its meta.
 *  iOS's twin is `LedgerOptions.build`. */
object LedgerOptions {
    fun build(groups: List<NamedGroupWire>, created: Map<String, String>, zone: ZoneId = ZoneId.systemDefault()): List<LedgerOption> =
        listOf(LedgerOption(LedgerChoice.JustMe, SpendingCopy.JUST_ME, LedgerMeta.JUST_ME_PARTS, null)) +
            groups.map { g ->
                LedgerOption(
                    LedgerChoice.Group(g.group.id), g.group.name,
                    LedgerMeta.groupParts(created[g.group.id]?.let(LedgerMeta::sinceMs), g.group.members.size, zone),
                    g.group,
                )
            }
}

/** A Just me expense as Add expense writes it: you paid, the whole is your share, no group (the personal scope). iOS's
 *  twin is `JustMeExpense`. */
object JustMeExpense {
    fun dto(
        you: String,
        totalMinor: Long,
        currency: String,
        note: String?,
        category: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): ExpenseDTO = ExpenseDTO(
        payer = you, paidMinor = totalMinor, currencyCode = currency, group = null, conversion = null,
        shares = listOf(ExpenseDTO.ShareDTO(id = you, amountMinor = totalMinor)),
        note = note, timestampMs = nowMs, category = category, addedBy = you,
    )

    /** An edit's new money: the stored payer keeps the whole (an edit never moves an expense between ledgers). */
    fun money(payer: String, totalMinor: Long, currency: String): ExpenseInput =
        ExpenseInput(payer, totalMinor, currency, listOf(IdAmount(payer, totalMinor)), null, null)
}

/** The page on screen: the + opens Add expense on Just me from it (plan section 3). The shell's state, read by the +. */
object SpendingPresence {
    var shown by mutableStateOf(false)
}

// ---- Views ----

/** The slice tones of "Where the money went", in order (the group page's). */
internal fun sliceColour(index: Int, dark: Boolean): Color = sliceTones[index % sliceTones.size].letter(dark)

/** Decision 164: the spending tile: ᛁ (Isa) in Vardr Runes Carved in Norse, on the person tile's diamond; your picture,
 *  or your initial, elsewhere (Vardr Runes is Norse-only). iOS's twin is `SpendingTile`. */
@Composable
fun SpendingTile(name: String?, avatar: Avatar, dark: Boolean, size: Int = ThemeArt.m("ledgerTile").toInt()) {
    if (Appearance.theme == AppTheme.NORSE) {
        val kind = TileShape.of(Appearance.theme, person = true)
        Box(Modifier.size(size.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .requiredSize(tileDrawnSide(kind, size).dp)
                    .tileEdge(kind, size)
                    .background(Tok.accentSoft(dark), tileShape(kind, size)),
                contentAlignment = Alignment.Center,
            ) {
                Text(SPENDING_RUNE, style = Type.rune((size * 0.62f).sp).copy(color = Tok.accent(dark)))
            }
        }
    } else {
        // Your picture, your initial, or the person glyph, on the soft fill (the accent is the account badge's alone).
        AvatarView(avatar, name, size.dp, dark)
    }
}

/** ᛁ (Isa). */
const val SPENDING_RUNE = "ᛁ"

/** The Just me / All my spending switch (one stored choice, card and page): the chosen half filled in the accent, the
 *  other in the dim, on the theme's pill. [compact] is the card's; the page's takes the full width. */
@Composable
fun SpendingSwitch(view: SpendingView, dark: Boolean, compact: Boolean = false, modifier: Modifier = Modifier, onPick: (SpendingView) -> Unit) {
    val shape = ThemeArt.pillShape(Appearance.theme)
    Row(
        modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .background(Tok.surfaceRaised(dark), shape)
            .padding(3.dp),
    ) {
        listOf(SpendingView.JUST_ME, SpendingView.ALL).forEach { option ->
            val chosen = option == view
            Box(
                Modifier
                    .then(if (compact) Modifier else Modifier.weight(1f))
                    .clip(shape)
                    .then(if (chosen) Modifier.background(Tok.accent(dark), shape) else Modifier)
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onPick(option) })
                    .padding(horizontal = if (compact) 10.dp else 8.dp, vertical = if (compact) 6.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    style = Type.name(dark, if (compact) TypeScale.footnote.toInt() else TypeScale.segment.toInt())
                        .copy(color = if (chosen) Tok.onAccent(dark) else Tok.textDim(dark)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = ((if (compact) TypeScale.footnote else TypeScale.segment) * 0.8f).sp,
                        maxFontSize = (if (compact) TypeScale.footnote else TypeScale.segment).sp,
                        stepSize = 0.2.sp,
                    ),
                )
            }
        }
    }
}

/** The thin stacked bar of one currency's slices (core's basis points), in the slice tones; an empty track with none. */
@Composable
fun SpendingBar(slices: List<CategoryShareOut>, dark: Boolean, height: Int = 6) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(ThemeArt.meterShape(Appearance.theme))
            // The track, drawn with no slices too (the empty card's bar).
            .background(Tok.ringTrack(dark))
            .clearAndSetSemantics {},
    ) {
        if (slices.isNotEmpty()) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            slices.forEachIndexed { i, c ->
                Box(Modifier.weight(maxOf(c.basisPoints.toFloat(), 1f)).fillMaxSize().background(sliceColour(i, dark)))
            }
        }
    }
}

/** Home's Your spending card (decision 163): the tile and the switch, "This month", the largest currency's total with
 *  "+{n} more", a chevron, and the thin category bar. A tap opens the page ([onOpen]). iOS's twin is `SpendingCard`. */
@Composable
fun SpendingCard(
    loaded: SpendingLoad.Loaded?,
    view: SpendingView,
    name: String?,
    avatar: Avatar,
    dark: Boolean,
    onView: (SpendingView) -> Unit,
    onOpen: () -> Unit,
) {
    val first = loaded?.out?.currencies?.firstOrNull()
    val more = maxOf((loaded?.out?.currencies?.size ?: 0) - 1, 0)
    Column(Modifier.fillMaxWidth().glassCard(dark), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClickLabel = SpendingCopy.HINT, onClick = onOpen).clearAndSetSemantics {}) {
                SpendingTile(name, avatar, dark)
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            SpendingSwitch(view, dark, compact = true, onPick = onView)
        }
        val spoken = SpendingCopy.spoken(view.label, first?.let { RolesMoney.spoken(it.totalMinor, it.currencyCode) })
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = SpendingCopy.HINT, role = Role.Button, onClick = onOpen)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                    onClick(label = SpendingCopy.HINT) { onOpen(); true }
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Type.kicker(SpendingCopy.THIS_MONTH), style = Type.microLabel(dark).copy(color = Tok.textDim(dark)))
                    if (first != null) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                RolesMoney.format(first.totalMinor, first.currencyCode) ?: Placeholder.NONE,
                                style = Type.numericColored(TypeScale.displayName + 4, Tok.text(dark), FontWeight.Bold),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = ((TypeScale.displayName + 4) * 0.6f).sp,
                                    maxFontSize = (TypeScale.displayName + 4).sp,
                                    stepSize = 0.2.sp,
                                ),
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (more > 0) {
                                Text(
                                    SpendingCopy.more(more),
                                    style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                    } else {
                        Text(SpendingCopy.EMPTY, style = Type.bodyDim(dark).copy(fontSize = TypeScale.prose.sp))
                    }
                }
                RowChevron(Tok.textDim(dark))
            }
            SpendingBar(first?.categories.orEmpty(), dark)
        }
    }
}

/** "Chart" / "Table" on the where card (SPEC 8.4: every chart has a table view). */
@Composable
fun ChartTableSwitch(table: Boolean, dark: Boolean, onTable: (Boolean) -> Unit) {
    val shape = ThemeArt.pillShape(Appearance.theme)
    Row(Modifier.background(Tok.surfaceRaised(dark), shape).padding(2.dp)) {
        listOf(false, true).forEach { isTable ->
            val chosen = isTable == table
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(shape)
                    .then(if (chosen) Modifier.background(Tok.accent(dark), shape) else Modifier)
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onTable(isTable) })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isTable) SpendingCopy.TABLE else SpendingCopy.CHART,
                    style = Type.name(dark, 12).copy(color = if (chosen) Tok.onAccent(dark) else Tok.textDim(dark)),
                )
            }
        }
    }
}

/** The page "Your spending": the switch, the month, Spent per currency (never converted), where the money went (Chart or
 *  Table, the largest currency's slices), and the month's expenses newest first. Reached from Home's card and from the
 *  Expenses tab's Just me header (decision 190; [fromJustMeHeader], decision 206). The + opens Add expense on Just me
 *  from here. Shown inside the tabs, so the bar stays under it, as a group's page. iOS's twin is `SpendingScreen`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpendingScreen(
    vaultPath: String,
    dark: Boolean,
    fromJustMeHeader: Boolean,
    onBack: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SpendingPrefs.load(context) }
    // Decision 206: the view this visit opened in until the switch is tapped; null reads the remembered one.
    var opened by remember { mutableStateOf<SpendingView?>(null) }
    val view = opened ?: SpendingPrefs.pageStart(fromJustMeHeader, SpendingPrefs.shownView)
    fun pick(v: SpendingView) {
        opened = v
        SpendingPrefs.setView(context, v)
    }
    var month by remember { mutableStateOf(SpendingMonth.current()) }
    var loaded by remember { mutableStateOf<SpendingLoad.Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var table by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val isCurrent = month == SpendingMonth.current()

    LaunchedEffect(vaultPath, SyncStatus.revision, view, month) {
        val read = withContext(Dispatchers.IO) { runCatching { SpendingLoad.load(LedgerStore(vaultPath), month, view) } }
        read.fold(
            onSuccess = { loaded = it; error = null },
            onFailure = { error = "Could not load the ledger. ${it.message ?: it}" },
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        SpendingPresence.shown = true
        onDispose { SpendingPresence.shown = false }
    }

    val scroll = rememberScrollState()
    val ownBackdrop = rememberBackdrop()
    val backdrop = LocalShellBackdrop.current ?: ownBackdrop
    val tree = CategoryLevels.tree
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = BarDecisions.scrollTop(16f).dp, bottom = 8.dp + LocalShellBarInset.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SpendingSwitch(view, dark, onPick = ::pick)
            if (view == SpendingView.ALL) {
                Text(SpendingCopy.ALL_NOTE, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            }
            // The month switcher: previous, the month, next (dimmed in the current month).
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonthButton(TabIcons.ChevronLeft, SpendingCopy.PREV, enabled = true, dark) { month = month.adding(-1) }
                Text(
                    month.label,
                    style = Type.name(dark, TypeScale.inlineTitle.toInt()),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                MonthButton(TabIcons.ChevronRight, SpendingCopy.NEXT, enabled = !isCurrent, dark) { month = month.adding(1) }
            }
            val l = loaded
            val e = error
            if (e != null) {
                Text(e, style = Type.body(dark).copy(color = Tok.owed(dark)), modifier = Modifier.fillMaxWidth().glassCard(dark))
            } else if (l != null) {
                if (l.out.rows.isEmpty()) {
                    Column(Modifier.fillMaxWidth().glassCard(dark), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (l.hasAny) {
                            Text(SpendingCopy.emptyMonth(month.name), style = Type.name(dark, TypeScale.emptyTitle.toInt()))
                        } else {
                            Text(SpendingCopy.EMPTY_TITLE, style = Type.name(dark, TypeScale.emptyTitle.toInt()))
                            Text(SpendingCopy.EMPTY_DETAIL, style = Type.bodyDim(dark))
                        }
                    }
                } else {
                    // Spent: the largest currency, the others as chips (never converted).
                    Column(Modifier.fillMaxWidth().glassCard(dark), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionLabel(if (isCurrent) SpendingCopy.THIS_MONTH else month.label, dark, color = Tok.text(dark))
                        Text(SpendingCopy.SPENT, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
                        l.out.currencies.firstOrNull()?.let { first ->
                            Text(
                                RolesMoney.format(first.totalMinor, first.currencyCode) ?: Placeholder.NONE,
                                style = Type.numericColored(TypeScale.largeTitle, Tok.text(dark), FontWeight.Bold),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = (TypeScale.largeTitle * 0.6f).sp,
                                    maxFontSize = TypeScale.largeTitle.sp,
                                    stepSize = 0.2.sp,
                                ),
                                modifier = Modifier.clearAndSetSemantics {
                                    contentDescription = RolesMoney.spoken(first.totalMinor, first.currencyCode)
                                },
                            )
                        }
                        val others = l.out.currencies.drop(1)
                        if (others.isNotEmpty()) {
                            FlowRow(
                                Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                others.forEach { c ->
                                    Text(
                                        RolesMoney.format(c.totalMinor, c.currencyCode) ?: Placeholder.NONE,
                                        style = Type.numericColored(TypeScale.pill, Tok.text(dark), FontWeight.SemiBold),
                                        modifier = Modifier
                                            .background(Tok.surfaceRaised(dark), ThemeArt.pillShape(Appearance.theme))
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                    l.out.currencies.firstOrNull()?.takeIf { it.categories.isNotEmpty() }?.let { c ->
                        WhereCard(c, table, tree, dark) { table = it }
                    }
                    SectionLabel(SpendingCopy.EXPENSES, dark, color = Tok.text(dark))
                    Column(Modifier.fillMaxWidth().glassCard(dark, padding = 14)) {
                        l.out.rows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(color = Tok.hairline(dark))
                            SpendingRow(row, tree, l.groupNames, dark) { onOpenExpense(row.id) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        PushedBar(
            title = SpendingCopy.TITLE,
            onBack = onBack,
            scroll = scroll,
            backdrop = backdrop,
            trailing = if (view == SpendingView.JUST_ME) ({
                Box {
                    BarIconItem(dark, SpendingCopy.EXPORT) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Tok.surfaceRaised(dark)) {
                        SkiptiWindowLocals {
                            DropdownMenuItem(
                                text = { Text(SpendingCopy.EXPORT, style = Type.body(dark)) },
                                onClick = {
                                    menu = false
                                    onExport()
                                },
                            )
                        }
                    }
                }
            }) else null,
        )
    }
}

/** The page bar's export item: the download glyph in the accent, in the bar's round control, a 48 dp target. */
@Composable
private fun BarIconItem(dark: Boolean, spoken: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = BarDecisions.controlShift(BarDecisions.ACCOUNT_CENTRE_FROM_EDGE).dp)
            .size(BarDecisions.TOUCH_TARGET.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.TopCenter,
    ) {
        BarCircle(dark, BarDecisions.BACK_BUTTON_SIZE) {
            Icon(TabIcons.Download, contentDescription = null, tint = Tok.accent(dark), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MonthButton(glyph: androidx.compose.ui.graphics.vector.ImageVector, spoken: String, enabled: Boolean, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else ButtonLook.DISABLED_ALPHA)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).background(Tok.surface(dark), ThemeArt.controlShape(Appearance.theme)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, contentDescription = null, tint = Tok.textDim(dark), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun WhereCard(c: CurrencySpendingOut, table: Boolean, tree: CategoryTree, dark: Boolean, onTable: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(dark), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(OverviewCopy.WHERE_IT_WENT, dark, color = Tok.text(dark))
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            ChartTableSwitch(table, dark, onTable)
        }
        if (table) {
            Row(Modifier.fillMaxWidth()) {
                Text(Type.kicker(SpendingCopy.COLUMNS[0]), style = Type.microLabel(dark).copy(fontSize = 12.sp, color = Tok.textDim(dark)), modifier = Modifier.weight(1f))
                Text(Type.kicker(SpendingCopy.COLUMNS[1]), style = Type.microLabel(dark).copy(fontSize = 12.sp, color = Tok.textDim(dark)))
                Text(
                    Type.kicker(SpendingCopy.COLUMNS[2]),
                    style = Type.microLabel(dark).copy(fontSize = 12.sp, color = Tok.textDim(dark)),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(52.dp),
                )
            }
            c.categories.forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).background(sliceColour(i, dark), ThemeArt.swatchShape(Appearance.theme)))
                    Text(CategoryLevels.name(s.category, tree), style = Type.body(dark).copy(fontSize = TypeScale.detail.sp), modifier = Modifier.weight(1f))
                    Text(
                        RolesMoney.format(s.amountMinor, c.currencyCode) ?: Placeholder.NONE,
                        style = Type.numericColored(TypeScale.detail, Tok.text(dark), FontWeight.SemiBold),
                    )
                    Text(
                        OverviewCopy.percent(s.basisPoints),
                        style = Type.numericColored(TypeScale.detail, Tok.textDim(dark)),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(52.dp),
                    )
                }
            }
        } else {
            c.categories.forEachIndexed { i, s ->
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(CategoryLevels.name(s.category, tree), style = Type.name(dark, TypeScale.detail.toInt()), modifier = Modifier.weight(1f))
                        Text(OverviewCopy.percent(s.basisPoints), style = Type.numericColored(TypeScale.detail, Tok.text(dark), FontWeight.SemiBold))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(ThemeArt.meterShape(Appearance.theme))
                            .background(Tok.ringTrack(dark))
                            .clearAndSetSemantics {},
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(maxOf(s.basisPoints.toFloat() / 10_000f, 0.01f))
                                .height(5.dp)
                                .background(sliceColour(i, dark)),
                        )
                    }
                }
            }
        }
    }
}

/** One expense of the page: what it was ("{group}, your share" under it for a group's in All), the day and the
 *  category, and the amount that counts (core's: the whole for Just me, your share for a group's). */
@Composable
fun SpendingRow(row: SpendingRowOut, tree: CategoryTree, groupNames: Map<String, String>, dark: Boolean, onOpen: () -> Unit) {
    val leaf = row.category?.let { CategoryLevels.name(it, tree) }
    val title = row.note?.takeIf { it.isNotEmpty() } ?: leaf ?: "Expense"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = OverviewCopy.OPEN_EXPENSE, onClick = onOpen)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.withRunes(), style = Type.name(dark, 15), maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.group?.let { g ->
                Text(groupNames[g]?.let(SpendingCopy::groupMeta) ?: OverviewCopy.YOUR_SHARE, style = Type.bodyDim(dark).copy(fontSize = 12.sp))
            }
            Text(
                Separator.annotated(listOfNotNull(AppDate.dayMonth(row.dateMs.toLong()), leaf)),
                style = Type.bodyDim(dark).copy(fontSize = 12.sp, color = Tok.textFaint(dark)),
            )
        }
        Text(
            RolesMoney.format(row.amountMinor, row.currencyCode) ?: Placeholder.NONE,
            style = Type.numericColored(15, Tok.text(dark), FontWeight.SemiBold),
        )
    }
}

/** The Ledger page's rows: Just me first ("Only you"), then each writable group with its tile and "Since {Month Year} ·
 *  {n} people", each in the theme's card, the chosen one checked. A pick writes through [onPick] and returns. iOS's twin
 *  is `LedgerChoicePage`. */
@Composable
fun LedgerChoicePage(
    options: List<LedgerOption>,
    chosen: LedgerChoice,
    name: String?,
    avatar: Avatar,
    dark: Boolean,
    onPick: (LedgerChoice) -> Unit,
    onClose: () -> Unit,
) {
    // Drawn in [PushedWindow], as Add expense's other choice pages. 14 apart: Norse's cards glow 6 outside their edge
    // (D-243), so 10 let two glows meet.
    PushedWindow(AddSheetCopy.LEDGER, dark, onClose) {
      Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        options.forEach { option ->
            val isChosen = option.choice == chosen
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (CardStyle.of(Appearance.theme) == CardStyle.NOTCHED) Modifier.norseEdgeGlow(Tok.accent(dark), ThemeArt.m("chamfer")) else Modifier)
                    .clip(ThemeArt.cardShape(Appearance.theme))
                    .selectable(selected = isChosen, role = Role.RadioButton, onClick = {
                        onPick(option.choice)
                        onClose()
                    })
                    .glassCard(dark, padding = 14)
                    .heightIn(min = 52.dp)
                    .clearAndSetSemantics {
                        contentDescription = YouRows.spoken(option.title, option.parts)
                        selected = isChosen
                        role = Role.RadioButton
                        onClick { onPick(option.choice); onClose(); true }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeArt.m("rowGap").dp),
            ) {
                val group = option.group
                if (group != null) GroupTile(group, dark, ThemeArt.m("ledgerTile").toInt()) else SpendingTile(name, avatar, dark)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(option.title.withRunes(), style = Type.name(dark, TypeScale.ledgerName.toInt()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Separator.annotated(option.parts),
                        style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isChosen) Icon(TabIcons.Check, contentDescription = null, tint = Tok.accent(dark), modifier = Modifier.size(20.dp))
            }
        }
      }
    }
}
