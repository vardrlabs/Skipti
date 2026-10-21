package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.CategoryTree
import uniffi.skipti_core.GroupStatsOut
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.OverviewGroupIn
import uniffi.skipti_core.OverviewOut
import uniffi.skipti_core.PersonNetOut
import uniffi.skipti_core.SettledProgressOut
import uniffi.skipti_core.StatsExpenseIn
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiCategoryTreeDefault
import uniffi.skipti_core.ffiGroupStats
import uniffi.skipti_core.ffiOverview
import uniffi.skipti_core.ffiSettledProgress
import kotlin.math.abs

/** Home's one read: every group with its viewer, its people and (D-210) the
 *  people removed from it, named, so a removed person at zero is taken out of
 *  the cards and one who still owes is named. The screen and the tests read
 *  Home through this. iOS's twin is `HomeOverview.load`. D-223: also each
 *  group's settled progress (core's, per currency) for its ledger card's meter,
 *  from the same expenses, settlements and redistributions (one vault read). */
object HomeOverview {
    data class Home(
        val named: List<NamedGroupWire>,
        val overview: OverviewOut,
        val progress: Map<String, List<SettledProgressOut>> = emptyMap(),
    )

    fun load(store: LedgerStore): Home {
        val named = store.namedGroups()
        val removed = store.removedPeople()
        val expenses = store.loadExpenses().map { it.toInput() }
        val settlements = store.loadSettlements().map { it.toInput() }
        val redistributions = store.loadRedistributions()
        val overview = ffiOverview(
            named.map { OverviewGroupIn(it.group.id, it.selfId, it.members, removed[it.group.id].orEmpty()) },
            expenses,
            settlements,
            redistributions,
        )
        // Settlement currency first (E4 Design 2), as the group page's Settled card orders them.
        val progress = named.associate {
            it.group.id to SettledSummary.blocks(
                ffiSettledProgress(expenses, settlements, redistributions, it.group.id), it.group.settlementCurrency,
            )
        }
        return Home(named, overview, progress)
    }
}

/** D-180: the Balance tab's words, one place; iOS's twin is `OverviewCopy`. */
object OverviewCopy {
    /** Decision 122 (the founder): a group with a change of this phone's the cloud refused (parked). One quiet line, no
     *  count, no button. iOS's twin is `OverviewCopy.couldNotSync` (read by the parity test). */
    const val COULD_NOT_SYNC = "One change could not sync. It is still on this phone."
    const val ACROSS = "Across all your groups"
    const val GET_BACK = "You'll get back"
    const val OWE = "You owe"
    const val SETTLED = "You're all settled up"
    const val SETTLED_DETAIL = "Nobody owes anybody. Nice."
    const val SETTLED_SHORT = "Settled"
    const val OPEN_EXPENSE = "Opens the expense, to edit or delete it"
    const val PEOPLE = "People"
    const val SETTLE_UP = "Settle up"
    fun groups(n: Int) = if (n == 1) "1 group" else "$n groups"
    fun chip(minor: Long, formatted: String) = if (minor > 0) "You get back $formatted" else "You owe $formatted"
    fun verb(minor: Long) = if (minor > 0) "owes you" else "you owe"
    const val TOTAL_SPENT = "Total spent"
    const val YOUR_SHARE = "Your share"
    const val WHERE_IT_WENT = "Where the money went"
    const val WHO_OWES = "Who owes whom"
    const val RECENT = "Recent"
    const val NO_CATEGORY = "No category"
    const val NOTHING_YET = "Nothing here yet. Add your first expense with the + below."
    /** D-220 (L25): the parts of the "settles in" line; the tile row draws them with `Separator.annotated` (Norse's
     *  gold rune), the white line over a photo the joined string. */
    fun settlesInParts(people: Int, currency: String) = listOf(GroupCopy.people(people), "settles in $currency")
    fun settlesIn(people: Int, currency: String) = settlesInParts(people, currency).joinToString(Separator.meta())
    fun paid(who: String, formatted: String) = "$who paid $formatted"

    /** Basis points as a whole percent; "<1%" for a slice that rounds to nothing. */
    fun percent(bps: UInt): String {
        val whole = (bps + 50u) / 100u
        return if (whole == 0u) "<1%" else "$whole%"
    }
}

/**
 * D-180: the category tree's two levels (SPEC §8.1: parents are headers,
 * only leaves are assigned). A group's page groups by parent; D-181's
 * two-step picker uses the same answers. iOS's twin: `CategoryLevels`.
 */
object CategoryLevels {
    val tree: CategoryTree by lazy { ffiCategoryTreeDefault() }

    fun parent(leaf: String?, tree: CategoryTree): String? =
        leaf?.let { id -> tree.categories.firstOrNull { it.id == id }?.parent }

    fun name(id: String?, tree: CategoryTree): String =
        id?.let { c -> tree.categories.firstOrNull { it.id == c }?.name } ?: OverviewCopy.NO_CATEGORY

    fun parents(tree: CategoryTree): List<Pair<String, String>> =
        tree.categories.filter { it.parent == null && !it.archived }.map { it.id to it.name }

    /** D-181: a sub-category survives a change of category only if it belongs
     *  to the new one. */
    fun keep(leaf: String?, parent: String?, tree: CategoryTree): String? =
        if (parent != null && parent(leaf, tree) == parent) leaf else null

    fun leaves(parent: String?, tree: CategoryTree): List<Pair<String, String>> =
        if (parent == null) emptyList()
        else tree.categories.filter { it.parent == parent && !it.archived }.map { it.id to it.name }
}

/** D-181: the two-step picker's words; iOS's twin is `CategoryCopy`. */
object CategoryCopy {
    const val CATEGORY = "Category"
    const val SUBCATEGORY = "Sub-category"
    const val NONE = "None"
    const val CHOOSE = "Choose one"
    fun hint(parent: String) = "Choose one to file it under $parent."
}

/** A person's avatar tone: the sum of the name's code points (lower-cased)
 *  modulo six, as iOS's `GroupTone.person`. */
fun personTone(name: String): GroupTone {
    var sum = 0
    name.lowercase().codePoints().forEach { sum += it }
    return GroupTone.entries[sum % GroupTone.entries.size]
}

private fun money(minor: Long, code: String) = BalanceUi.moneyFormat(minor, code, AppLocale.current)

private val sliceTones = listOf(GroupTone.CORAL, GroupTone.BLUE, GroupTone.TEAL, GroupTone.AMBER, GroupTone.SKY, GroupTone.PINK)

/** D-223 fix wave (F6): Home's place, kept by the shell above its route switch (a pushed route replaces the tabs, so
 *  Home leaves the composition): its last read and its scroll state. Home draws from them at once when it comes back,
 *  so the kept offset is never clamped by an empty first frame, then reads again as before; iOS's NavigationStack keeps
 *  Home the same way. Memory only, for as long as the shell runs. */
class HomeKeep(val scroll: ScrollState = ScrollState(0)) {
    val groups = mutableStateOf<List<NamedGroupWire>>(emptyList())
    val overview = mutableStateOf<OverviewOut?>(null)
    // D-223: each group's settled blocks (its card's meter), and Lately's read (null until read).
    val progress = mutableStateOf<Map<String, List<SettledProgressOut>>>(emptyMap())
    val latelyRead = mutableStateOf<ActivityFeed.Loaded?>(null)
    val needsName = mutableStateOf(false)
    val loaded = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    /** Follow-up (A-R3): one read of Home, taken by [take] on the main thread. */
    fun take(read: Result<Pair<HomeOverview.Home, ActivityFeed.Loaded?>>) {
        read.fold(
            onSuccess = { (home, lately) ->
                groups.value = home.named
                overview.value = home.overview
                progress.value = home.progress
                latelyRead.value = lately
                needsName.value = false
                error.value = null
            },
            onFailure = {
                if (it is NeedsYourNameException) needsName.value = true
                else error.value = "Could not load the ledger. ${it.message ?: it}"
            },
        )
        loaded.value = true
    }

    companion object {
        /** Follow-up (A-R3): Home's read, off the main thread: the overview and, only with groups, Lately (D-223: read
         *  only, inside Home's own read, so a failure is Home's error line, never an empty feed; without groups the read
         *  would write the self record on a fresh phone). Home and a create that returns to it read through this. */
        fun read(open: () -> LedgerStore): Result<Pair<HomeOverview.Home, ActivityFeed.Loaded?>> = runCatching {
            val store = open()
            val home = HomeOverview.load(store)
            home to if (home.named.isEmpty()) null else ActivityFeed.load(store, group = null)
        }
    }
}

/**
 * D-180: Balance across every group (the founder's canvas): what you get back
 * or owe, one total per currency and never converted; the people behind it;
 * and each group, which opens its page. Draws on the shell's glow, not its
 * own (the old Balance painted a second glow: a seam under the account bar).
 */
@Composable
fun OverviewScreen(
    vaultPath: String,
    dark: Boolean,
    onSettle: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
    /** D-217: the account glyph, the bar's trailing item (null: none, as a proof that has no account page). */
    onOpenAccount: (() -> Unit)? = null,
    onAddYourName: () -> Unit,
    onImport: () -> Unit,
    onJoin: () -> Unit,
    /** C2: the account's username as the shell knows it (null until read),
     *  kept across tabs and routes as iOS's tab keeps its state. */
    username: UsernameState? = null,
    onUsername: (UsernameState?) -> Unit = {},
    onPickUsername: () -> Unit = {},
    /** D-223: a Lately row opens its expense (the shell's `expense/<id>` route). */
    onOpenExpense: (String) -> Unit = {},
    /** D-223 A1: Lately's "See all" opens the whole ledger's Activity screen. */
    onSeeAllActivity: () -> Unit = {},
    /** D-223 fix wave (F6): Home's place, the shell's (kept across a pushed route); a screen of its own without one. */
    keep: HomeKeep = remember { HomeKeep() },
) {
    val knownUsername by rememberUpdatedState(username)
    val groups by keep.groups
    val overview by keep.overview
    val progress by keep.progress
    val latelyRead by keep.latelyRead
    val needsName by keep.needsName
    val loaded by keep.loaded
    val error by keep.error

    LaunchedEffect(vaultPath, SyncStatus.revision) {
        // D-223: Lately, read only (Home never marks the feed seen), inside Home's own read: a failure is Home's error
        // line, never an empty feed (CLAUDE.md 10.1; iOS's twin). D-224 follow-up (A-R3): one read for Home and a create.
        keep.take(withContext(Dispatchers.IO) { HomeKeep.read { LedgerStore(vaultPath) } })
        // C2: once the account has a username it never loses one (changed,
        // never removed), so the cloud is not asked again. A failed read
        // (null) keeps what was known (UsernameState.merge).
        if (!runCatching { Account.isSignedIn() }.getOrDefault(false)) {
            onUsername(null)
        } else if (knownUsername == null || knownUsername == UsernameState.None) {
            val read = People.accountUsername()
            onUsername(
                UsernameState.merge(
                    known = knownUsername, read = read,
                    signedIn = runCatching { Account.isSignedIn() }.getOrDefault(false),
                ),
            )
        }
    }

    // D-223 fix wave (F5): iOS's empty Home is its own stack, 12 apart, the Norse ornament its first child.
    val emptyHome = needsName || (loaded && groups.isEmpty() && error == null)
    TabRootScaffold(
        title = "Balance",
        ornament = true,
        trailing = accountGlyph(dark, onOpenAccount),
        scroll = rememberShellScrollState(keep.scroll),
        iosPadding = BarDecisions.TAB_ROOT_ROOMY_PADDING,
        iosOrnamentSpacing = if (emptyHome) HomeEmpty.SPACING else BarDecisions.CONTENT_SPACING,
    ) {
        when {
            needsName -> EmptyHome(
                dark, "Add your name",
                "This ledger has a group from before names existed. Add your name once; you will see yourself as \"Name (You)\".",
                listOf("Add your name" to onAddYourName),
            )
            // D-234 (L13): nothing under the title until the first read (iOS reads before its first frame), so a fresh
            // install never flashes the date and greeting before its empty state.
            !loaded -> Unit
            loaded && groups.isEmpty() && error == null -> {
                EmptyHome(
                    dark, "Nothing here yet",
                    "Create a group, join one you were invited to, or import from another app.",
                    listOf("Create a group" to onCreateGroup, InviteCopy.JOIN_TITLE to onJoin, ImportCopy.ENTRY to onImport),
                )
                // C2: an account signed in before usernames were asked, with
                // no groups yet: under the three actions, as iOS.
                if (username == UsernameState.None) UsernameCard(dark, onPickUsername)
            }
            else -> {
                // D-206 (the founder's item 5): a greeting by name and time of day.
                HomeGreeting(vaultPath, dark)
                // C2: an account signed in before usernames were asked.
                if (username == UsernameState.None) UsernameCard(dark, onPickUsername)
                overview?.let { Hero(it, groups.size, dark, onSettle) }
                // D-223 (L52, L54): "Your ledgers", a grid of cards with their meters; the old horizontal scroller is gone (L55).
                if (groups.isNotEmpty()) {
                    // D-223 fix wave (F4): iOS's `section()` pads 4 above Home's labels.
                    SectionLabel(LedgerCopy.TITLE, dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))
                    LedgerGrid(groups.size) { i, modifier ->
                        val named = groups[i]
                        GroupLedgerCard(
                            named,
                            overview?.groups?.firstOrNull { it.groupId == named.group.id }?.nets.orEmpty(),
                            progress[named.group.id].orEmpty(),
                            dark,
                            onOpen = { onOpenGroup(named.group.id) },
                            modifier = modifier,
                        )
                    }
                }
                // D-223 (L53): the newest few of the feed, only when there is something to show.
                val lately = latelyRead
                if (lately != null && lately.entries.isNotEmpty()) LatelyCard(
                    lately, dark,
                    onOpenExpense = onOpenExpense,
                    onSeeAll = onSeeAllActivity,
                )
                val people = overview?.people.orEmpty()
                if (people.isNotEmpty()) {
                    SectionLabel(OverviewCopy.PEOPLE, dark, Modifier.padding(top = 4.dp), color = Tok.text(dark))
                    // D-234 wave (decision 102): iOS pads this card 14.
                    Column(Modifier.glassCard(dark, padding = 14)) {
                        people.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(color = Tok.hairline(dark))
                            PersonRow(p, groups, dark)
                        }
                    }
                }
            }
        }
        error?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark)), modifier = Modifier.glassCard(dark)) }
        Spacer(Modifier.height(8.dp))
    }
}

/** D-223 fix wave (F5): Balance's empty state (iOS's `empty()`): its stack's spacing, which is also the gap under the
 *  Norse ornament on iOS (the ornament is that stack's first child). */
object HomeEmpty {
    const val SPACING = 12
}

@Composable
private fun EmptyHome(dark: Boolean, title: String, detail: String, actions: List<Pair<String, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(HomeEmpty.SPACING.dp)) {
        // D-223: the same title as the drawn empty state (the name role at the empty title's size).
        Text(title, style = Type.name(dark, TypeScale.emptyTitle.toInt()))
        Text(detail, style = Type.bodyDim(dark))
        actions.forEachIndexed { i, (label, act) ->
            if (i == 0) PrimaryAction(label, dark, onClick = act) else SecondaryAction(label, dark, onClick = act)
        }
    }
}

/** D-217: the hero's first line is a kicker; in Vardr Labs it takes steel (the accent), as the Look Book's hero does.
 *  The twin of iOS's `HeroKicker`. */
object HeroKicker {
    fun usesSteel(theme: AppTheme): Boolean = theme == AppTheme.VARDR
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(o: OverviewOut, groupCount: Int, dark: Boolean, onSettle: () -> Unit) {
    val first = o.totals.firstOrNull()
    val dim = Tok.onHero(dark).copy(alpha = 0.82f)
    Column(
        Modifier
            .fillMaxWidth()
            .heroArt(dark, ThemeArt.cardShape(Appearance.theme))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // D-217: a kicker (the micro role) at the date line's size; steel in Vardr Labs, the on-hero tone elsewhere.
        val heroKickerColour = if (HeroKicker.usesSteel(Appearance.theme)) Tok.accent(dark) else dim
        Text(
            Type.kicker(OverviewCopy.ACROSS),
            style = Type.microLabel(dark).copy(fontSize = TypeScale.dateLine.sp, color = heroKickerColour),
        )
        if (first != null) {
            Column {
                Text(
                    if (first.amountMinor > 0) OverviewCopy.GET_BACK else OverviewCopy.OWE,
                    style = Type.body(dark).copy(color = dim, fontSize = TypeScale.heroLine.sp),
                )
                Text(
                    money(abs(first.amountMinor), first.currencyCode),
                    // The numbers face (O-1), as iOS draws it: a money amount is a figure, not a title.
                    style = Type.numericColored(TypeScale.heroAmount, Tok.onHero(dark), androidx.compose.ui.text.font.FontWeight.Bold),
                    // D-217 final fix: shrinks to fit as iOS's does (minimumScaleFactor 0.6), never cut.
                    autoSize = amountAutoSize(TypeScale.heroAmount),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                // D-223 (Q4 a, LB 93): who owes, the first two people in the hero's currency (largest first), joined by
                // the separator (Norse's gold rune); not drawn when nobody is in it.
                val owes = WhoOwes.parts(o.people, first.currencyCode) { m, c -> money(m, c) }
                if (owes.isNotEmpty()) {
                    Text(
                        Separator.annotated(owes),
                        style = Type.body(dark).copy(color = dim, fontSize = TypeScale.heroLine.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Text(OverviewCopy.SETTLED, style = Type.display(dark).copy(fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Tok.onHero(dark)))
            Text(OverviewCopy.SETTLED_DETAIL, style = Type.body(dark).copy(color = dim, fontSize = TypeScale.heroLine.sp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (o.totals.drop(1).map { OverviewCopy.chip(it.amountMinor, money(abs(it.amountMinor), it.currencyCode)) } +
                OverviewCopy.groups(groupCount)).forEach {
                // D-220 (L19): the hero pill (filled in Aurora and Norse, outlined in Vardr Labs).
                Text(
                    it,
                    style = Type.label(dark, 13).copy(color = ThemePills.text(PillTone.HERO, dark)),
                    modifier = Modifier
                        .themePill(PillTone.HERO, dark)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (first != null) {
            if (ButtonLook.of(Appearance.theme) == ButtonLook.OUTLINED) {
                // D-220 (L24): Vardr Labs' hero Settle up is outlined: transparent, a 1 dp onHero border, the words in
                // onHero through ButtonLook (uppercase) at .08 em, square; pressed shows an onHero film (art.py
                // section 14). Twin of iOS's HeroButtonBody.
                val press = remember { MutableInteractionSource() }
                val pressed by press.collectIsPressedAsState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Tok.onHero(dark).copy(alpha = if (pressed) Aurora.HERO_CHIP_ALPHA else 0f), RectangleShape)
                        .border(1.dp, Tok.onHero(dark), RectangleShape)
                        .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onSettle)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ButtonLook.label(OverviewCopy.SETTLE_UP, Appearance.theme),
                        style = Type.label(dark, 16).copy(color = Tok.onHero(dark), letterSpacing = 0.08.em),
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(ThemeArt.controlShape(Appearance.theme))
                        .background(Tok.onHero(dark))
                        .clickable(onClick = onSettle)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(OverviewCopy.SETTLE_UP, style = Type.label(dark, 16).copy(color = Tok.heroStart(dark)))
                }
            }
        }
    }
}

@Composable
private fun PersonRow(p: PersonNetOut, groups: List<NamedGroupWire>, dark: Boolean) {
    val colour = if (p.netMinor > 0) Tok.owing(dark) else Tok.owed(dark)
    val where = p.groupIds.mapNotNull { id -> groups.firstOrNull { it.group.id == id }?.group?.name }.joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // D-213 (fix wave 2): the shared avatar, so it follows the theme's person shape.
        PersonAvatar(p.name, dark, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = Type.name(dark, 16))
            Text(where, style = Type.bodyDim(dark).copy(fontSize = 13.sp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(OverviewCopy.verb(p.netMinor), style = Type.label(dark, 12).copy(color = colour))
            Text(money(abs(p.netMinor), p.currencyCode), style = Type.numericColored(17, colour))
        }
    }
}

/**
 * D-180: one group's page: its tone, three numbers, where the money went, who
 * owes whom (the balance lines), and the newest expenses. Opening it makes it
 * the group Add, Expenses and Settle up use. Shown inside the tabs, so the
 * bar stays under it, as on iOS and the canvas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupPage(
    vaultPath: String,
    groupId: String,
    dark: Boolean,
    onBack: () -> Unit,
    onPeople: (String) -> Unit,
    onOpenExpense: (String) -> Unit,
    onActivity: (String) -> Unit = {},
    /** D-210: the name, picture and currency, and deleting the group. */
    onSettings: (String) -> Unit = {},
    /** D-210: a request card's Review opens the removal (group, member). */
    onReview: (String, String) -> Unit = { _, _ -> },
    /** D-210: a request card's Settle up opens that tab. */
    onSettleUp: () -> Unit = {},
    /** D-210: a copy exports its own group. */
    onExport: (String) -> Unit = {},
    /** D-221 (Design 12): the shell's memory of this open group between loads (the last settled state, a waiting play). */
    celebration: CelebrationState = remember { CelebrationState() },
) {
    var named by remember { mutableStateOf<NamedGroupWire?>(null) }
    // D-210: the cloud, roles and requests of this group, and whether it is a
    // read-only copy (removed from, or deleted): nothing can be added to it.
    var people by remember { mutableStateOf(GroupPeople()) }
    var cardBusy by remember { mutableStateOf(false) }
    var cardBalances by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Every name the group's history needs (`historyNames`): removed people too.
    var historyNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Decision 122: a record of this group the cloud refused is parked on this phone (`CloudRecords.hasParked`).
    var parkedHere by remember { mutableStateOf(false) }
    // A read of the group failed (the screen says so): whether it is a copy is
    // not known, so nothing that changes it is offered (fail closed). The
    // banner and the copy's own actions need the mark itself.
    var loadFailed by remember { mutableStateOf(false) }
    val readOnly = people.readOnly
    val isCopy = readOnly != null || loadFailed
    val requestIds = Notices.requests(groupId).map { it.id }
    var stats by remember { mutableStateOf<GroupStatsOut?>(null) }
    var lines by remember { mutableStateOf<List<BalanceLineOut>>(emptyList()) }
    var remaining by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf<List<uniffi.skipti_core.ProjectedExpenseOut>>(emptyList()) }
    // D-221 (Design 1, 6, 11): core's settled progress per currency, and the milestone showing now.
    var progress by remember { mutableStateOf<List<SettledProgressOut>>(emptyList()) }
    var milestone by remember { mutableStateOf<Milestones.Shown?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // D-187: the group's picture, chosen from its tile.
    val pictureScope = rememberCoroutineScope()
    var pictureError by remember { mutableStateOf<String?>(null) }
    val choosePicture = rememberGroupPictureChooser(
        dark,
        onChosen = { emoji, jpeg ->
            pictureScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val store = LedgerStore(vaultPath)
                        store.setGroupPicture(groupId, emoji, jpeg)
                        GroupPictures.load(store)
                    }.isSuccess
                }
                pictureError = if (saved) null else GroupPictures.TOO_BIG
                if (saved) {
                    CloudSync.ledgerChanged(vaultPath)
                    // D-224 (Design 7): "Use letter" (no emoji, no photo) removes the picture.
                    DoneCenter.post(
                        DoneMoments.line(if (emoji == null && jpeg == null) DoneCopy.GROUP_PICTURE_REMOVED else DoneCopy.GROUP_PICTURE_SAVED),
                    )
                }
            }
        },
        onError = { pictureError = it },
    )

    LaunchedEffect(vaultPath, groupId, SyncStatus.revision, requestIds) {
        withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                // The roles this phone last heard, offline too.
                GroupRoles.load(store, groupId)
                // D-210: read strictly, before anything else: a copy is never the
                // group Add and Settle up work on, and an unreadable mark shows
                // nothing rather than a page that could be added to.
                val loaded = GroupPeople.load(store, groupId)
                if (loaded.readOnly == null) store.selectGroup(groupId)
                val n = store.namedGroups().first { it.group.id == groupId }
                val all = store.loadExpenses().filter { it.group == groupId }
                val tree = CategoryLevels.tree
                val s = ffiGroupStats(
                    all.map { StatsExpenseIn(it.toInput(), CategoryLevels.parent(it.category, tree)) },
                    n.selfId,
                )
                val settlements = store.loadSettlements().map { it.toInput() }
                val redistributions = store.loadRedistributions()
                val removed = store.removedIds(groupId)
                fun lineOf(who: String) = ffiBalanceLine(all.map { it.toInput() }, settlements, redistributions, removed, who, groupId)
                val l = lineOf(n.selfId)
                    .sortedWith(compareByDescending<BalanceLineOut> { abs(it.viewerNetMinor) }.thenBy { it.currencyCode })
                // D-180: core's projection (edits applied, deletions out), so a
                // row opens the expense itself, to read, edit or delete.
                val projected = store.projectedExpenses().expenses
                    .filter { !it.deleted && it.expense.group == groupId }
                    .sortedByDescending { (it.timestampMs ?: it.addedAtMs).toLong() }
                    .take(5)
                Loaded(
                    Quad(n, s, l, projected),
                    // D-221 fix wave (S2): what the next card counts, the group's open pairwise payments (every member's
                    // and every removed person's frame).
                    GroupNext.remaining(n.members.map { it.id }, removed, ::lineOf), loaded, RequestCards.balanceLines(store, groupId, Notices.requests(groupId)),
                    store.historyNames(groupId),
                    SettledSummary.blocks(GroupProgress.progress(store, groupId), n.group.settlementCurrency),
                    GroupProgress.milestone(store, groupId, System.currentTimeMillis()),
                    CloudRecords.hasParked(store, groupId),
                )
            }
        }.fold(
            onSuccess = {
                val q = it.quad
                named = q.a; stats = q.b; lines = q.c; recent = q.d
                remaining = it.remaining
                people = it.people
                cardBalances = it.cardBalances
                historyNames = it.historyNames
                progress = it.progress
                milestone = it.milestone
                parkedHere = it.parked
                // D-221 (Design 12): a change to all settled marks a play; it starts once the page is seen, never here.
                val after = SettledSummary.allSettled(it.progress)
                if (Celebration.plays(celebration.before, after, isCopy = it.people.readOnly != null)) celebration.pending = true
                // S8: a play that has not started yet is dropped when a later load finds the group unsettled again.
                if (!after) celebration.pending = false
                celebration.before = after
                loadFailed = false
                error = null
            },
            // The last good page stays (an open dialog hangs on it); the error
            // says the reload failed, and nothing that changes the group is offered.
            onFailure = {
                loadFailed = true
                error = "Could not load the group. ${it.message ?: it}"
            },
        )
    }

    val rows = named?.let {
        PeopleRoles.rows(
            it.members, it.selfId, people.viewerYou, people.claims, GroupRoles.roles[groupId],
            yourPlaces = GroupRoles.roles[groupId]?.places?.you, otherYouIds = people.otherYouIds,
        )
    } ?: emptyList()
    val cards = if (named != null && people.shared && !isCopy) RequestCards.cards(Notices.requests(groupId), rows) else emptyList()
    val viewerName = named?.members?.firstOrNull { it.id == named?.selfId }?.name ?: ""
    fun decline(item: Notices.Item, group: NamedGroupWire) {
        cardBusy = true
        pictureScope.launch {
            try {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { RoleCalls.decline(item, group, viewerName) }
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = RolesCopy.failure(e, person = true)
            } finally {
                cardBusy = false
            }
        }
    }

    // D-217: a pushed page. The bar (the round back button and the group's name, as iOS's inline title) overlays the
    // content, which starts under it and scrolls beneath.
    val scroll = rememberScrollState()
    // D-220 (L-D217-3): the page is in the shell, under the tab bar: it records into the shell's backdrop (its own bar
    // and the tab bar blur it) and keeps the tab bar's height clear at its end.
    val ownBackdrop = rememberBackdrop()
    val backdrop = LocalShellBackdrop.current ?: ownBackdrop
    // D-221 (Design 12): the celebration starts when a play is pending, the page is composed (it is the visible route:
    // a pushed page, another tab or the lock replaces it) and the screen is resumed (ON_RESUME).
    val motion = rememberMotionState()
    var play by remember { mutableStateOf<CelebrationPlay?>(null) }
    LaunchedEffect(celebration.pending, motion.resumed) {
        if (celebration.pending && motion.resumed) {
            celebration.pending = false
            play = CelebrationPlay(still = !Celebration.showsBurst(motion.reduce))
        }
        // S8: leaving the page mid-play ends it (composition does it for a pushed page or another tab; ON_PAUSE here, so
        // a backgrounded play never keeps the page dimmed until it is resumed). It does not play again.
        if (!motion.resumed) play = null
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                // The page's content dims under the celebration; the art behind it does not.
                .graphicsLayer { alpha = play?.let { CelebrationTimeline.dim(it.elapsed, it.still) } ?: 1f }
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = BarDecisions.scrollTop(16f).dp, bottom = 8.dp + LocalShellBarInset.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val n = named
            if (n != null) {
                readOnly?.let { ReadOnlyBannerCard(n.group.name, it, dark) }
                // D-221 (Q16 a, Design 7): the bare header, the Settled card, the stats as a row, the milestone, the
                // next payment, then the request cards and everything below as before.
                GroupHeader(n, dark, isCopy, onPeople = onPeople, onPicture = { choosePicture() })
                pictureError?.let { ErrorLine(it, dark) }
                // Decision 122: one quiet line under the header, as the header's own meta line.
                if (parkedHere) Text(OverviewCopy.COULD_NOT_SYNC, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
                if (progress.isNotEmpty()) SettledCard(progress, dark)
                StatsRow(n, stats, lines.firstOrNull(), dark)
                milestone?.let { MilestoneCard(it, dark) }
                val next = GroupNext.pick(lines)
                if (!isCopy && next != null) GroupNextCard(
                    vaultPath, n, historyNames, next, remaining, dark, onError = { error = it },
                    // D-224 fix wave (F7): the card's payment settled the group: the play is marked at once.
                    onAllSettled = { celebration.settledHere(isCopy) },
                )
                cards.forEach { card ->
                    RequestCardView(
                        card, dark, canSettleUp = lines.any { it.viewerNetMinor != 0L }, balance = cardBalances[card.id], busy = cardBusy,
                        onDecline = { decline(card.item, n) },
                        onReview = { card.item.memberId?.let { onReview(groupId, it) } },
                        onSettle = onSettleUp,
                    )
                }
                stats?.takeIf { it.categories.isNotEmpty() }?.let { s ->
                    val tree = CategoryLevels.tree
                    Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel(OverviewCopy.WHERE_IT_WENT, dark, color = Tok.text(dark))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                // D-220 (L22): the meter: a capsule in Aurora, square elsewhere.
                                .clip(ThemeArt.meterShape(Appearance.theme))
                                .clearAndSetSemantics {},
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            s.categories.forEachIndexed { i, c ->
                                Box(
                                    Modifier
                                        .weight(maxOf(c.basisPoints.toFloat(), 1f))
                                        .fillMaxSize()
                                        .background(sliceTones[i % sliceTones.size].letter(dark)),
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.categories.forEachIndexed { i, c ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                                    Box(Modifier.size(8.dp).background(sliceTones[i % sliceTones.size].letter(dark), ThemeArt.swatchShape(Appearance.theme)))
                                    Spacer(Modifier.width(6.dp))
                                    Text(CategoryLevels.name(c.category, tree) + "  ", style = Type.bodyDim(dark).copy(fontSize = 13.sp))
                                    Text(OverviewCopy.percent(c.basisPoints), style = Type.body(dark).copy(fontSize = 13.sp))
                                }
                            }
                        }
                    }
                }
                if (lines.isNotEmpty()) {
                    SectionLabel(OverviewCopy.WHO_OWES, dark, color = Tok.text(dark))
                    val names = LedgerStore.balanceNames(n, historyNames)
                    lines.forEach { line ->
                        Box(Modifier.glassCard(dark)) { BalanceLineView(line, names, n.selfId, dark) }
                    }
                }
                SectionLabel(OverviewCopy.RECENT, dark, color = Tok.text(dark))
                if (recent.isEmpty()) {
                    Text(OverviewCopy.NOTHING_YET, style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark))
                } else {
                    // D-234 wave (decision 102): iOS pads this card 14.
                    Column(Modifier.glassCard(dark, padding = 14)) {
                        recent.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(color = Tok.hairline(dark))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                        .then(if (isCopy) Modifier else Modifier.clickable(onClickLabel = OverviewCopy.OPEN_EXPENSE) { onOpenExpense(e.id) })
                                    .padding(vertical = 9.dp)
                                    .semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.note?.takeIf { it.isNotBlank() } ?: CategoryLevels.name(e.category, CategoryLevels.tree),
                                        style = Type.name(dark, 15), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        OverviewCopy.paid(LedgerStore.label(e.expense.payer, n, historyNames), money(e.expense.paidMinor, e.expense.currencyCode)),
                                        style = Type.bodyDim(dark).copy(fontSize = 13.sp),
                                    )
                                }
                                if (!isCopy) RowChevron(Tok.textFaint(dark))
                            }
                        }
                    }
                }
                // D-196: what the others did here (D-189's list, for this group).
                SecondaryAction(ActivityFeed.GROUP_TITLE, dark) { onActivity(n.group.id) }
                if (readOnly != null) {
                    ReadOnlyActions(vaultPath, n, readOnly, dark, onExport = { onExport(n.group.id) }, onForgotten = onBack)
                } else if (!loadFailed) {
                    SecondaryAction(GroupsCopy.people(n.group.name), dark) { onPeople(n.group.id) }
                    // D-210: the name, picture and currency, and deleting the group.
                    SecondaryAction(RolesCopy.GROUP_SETTINGS_TITLE, dark) { onSettings(n.group.id) }
                }
            }
            error?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark)), modifier = Modifier.glassCard(dark)) }
            Spacer(Modifier.height(8.dp))
        }
        // GUARANTEES #2: over the content, under the bar, never taking a touch.
        play?.let { p -> CelebrationOverlay(p, named?.group?.name ?: "", dark, motion, onDone = { if (play === p) play = null }) }
        PushedBar(title = named?.group?.name ?: "", onBack = onBack, scroll = scroll, backdrop = backdrop)
    }
}

/** D-221 (Q16 a, L46): the group page's bare header, the Look Book's: no card and no tone tint. With a photo, the photo
 *  band (C2's frame at radius 18, the name white over it); else the tile row (the tile at `groupTile`, the name at the
 *  display name's size, C2's detail line, add people). The twin of iOS's `header(_:)`. */
@Composable
private fun GroupHeader(n: NamedGroupWire, dark: Boolean, isCopy: Boolean, onPeople: (String) -> Unit, onPicture: () -> Unit) {
    val photo = GroupPictures.byId[n.group.id]?.photoHex?.let { GroupPictures.bitmap(it) }
    // D-187 (the founder): the group's photo edge to edge across
    // the top of its card, the name white over `PhotoCard`'s
    // gradient; tapping the photo changes it.
    if (photo != null) Box(
        Modifier
            .fillMaxWidth()
            .height(PhotoCard.HEADER_HEIGHT.dp)
            // D-220 (L21): the card's frame (Norse's edge, Vardr Labs' white marks) at radius 18.
            .photoFrame(18, dark)
            .then(if (isCopy) Modifier else Modifier.clickable(onClickLabel = GroupPictures.TITLE) { onPicture() }),
    ) {
        PhotoBackdrop(photo, Modifier.matchParentSize())
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(n.group.name, style = Type.display(false, 700).copy(fontSize = TypeScale.displayName.sp, color = Color.White))
            Text(
                OverviewCopy.settlesIn(n.members.size, n.group.settlementCurrency).withRunes(),
                style = Type.body(false).copy(color = Color.White, fontSize = TypeScale.detail.sp),
            )
        }
        // D-220 (L21): the photo pill at iOS's 40 dp (a circle in Aurora, notched, square).
        // D-220 fix wave (I2): a 48 dp touch target round the 40 dp visual, which stays 8 dp from the corner.
        if (!isCopy) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(48.dp)
                .clickable(role = Role.Button) { onPeople(n.group.id) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(40.dp).themePill(PillTone.PHOTO, dark),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(TabIcons.UserPlus, contentDescription = MemberCopy.ADD_PEOPLE, tint = ThemePills.text(PillTone.PHOTO, dark))
            }
        }
    } else Row(verticalAlignment = Alignment.CenterVertically) {
        // D-221 fix wave (S5): the tile is laid out by its drawn side (Norse's diamond is 48 x sqrt 2), so its tips stay
        // inside the page's margin and clear of the name; the other shapes draw at 48 and are unchanged.
        val side = ThemeArt.m("groupTile").toInt()
        val drawn = tileDrawnSide(TileShape.of(Appearance.theme, person = false), side)
        Box(
            Modifier
                .size(drawn.dp)
                .then(if (isCopy) Modifier else Modifier.clickable(onClickLabel = GroupPictures.TITLE) { onPicture() }),
            contentAlignment = Alignment.Center,
        ) {
            GroupTile(n.group, dark, side)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(n.group.name, style = Type.display(dark, 700).copy(fontSize = TypeScale.displayName.sp, color = Tok.text(dark)))
            // D-220 (L25): Norse's gold rune between the parts.
            Text(
                Separator.annotated(OverviewCopy.settlesInParts(n.members.size, n.group.settlementCurrency)),
                style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
            )
        }
        // D-186 (the founder): add people right from the group.
        if (!isCopy) androidx.compose.material3.IconButton(onClick = { onPeople(n.group.id) }) {
            androidx.compose.material3.Icon(
                TabIcons.UserPlus,
                contentDescription = MemberCopy.ADD_PEOPLE,
                tint = Tok.accent(dark),
            )
        }
    }
}

/** D-221 (Q16 a): the three stat tiles as a row under the Settled card (they sat in the header's card before). */
@Composable
private fun StatsRow(n: NamedGroupWire, stats: GroupStatsOut?, net: BalanceLineOut?, dark: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val zero = money(0, n.group.settlementCurrency)
        Stat(OverviewCopy.TOTAL_SPENT, stats?.spent?.firstOrNull()?.let { money(it.amountMinor, it.currencyCode) } ?: zero, Tok.text(dark), dark, Modifier.weight(1f))
        Stat(OverviewCopy.YOUR_SHARE, stats?.yourShare?.firstOrNull()?.let { money(it.amountMinor, it.currencyCode) } ?: zero, Tok.text(dark), dark, Modifier.weight(1f))
        if (net != null && net.viewerNetMinor != 0L) {
            Stat(
                if (net.viewerNetMinor > 0) OverviewCopy.GET_BACK else OverviewCopy.OWE,
                money(abs(net.viewerNetMinor), net.currencyCode),
                if (net.viewerNetMinor > 0) Tok.owing(dark) else Tok.owed(dark),
                dark, Modifier.weight(1f),
            )
        } else {
            Stat(OverviewCopy.SETTLED_SHORT, Placeholder.NONE, Tok.textDim(dark), dark, Modifier.weight(1f))
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** What a group page reads in one go (off the main thread). */
private class Loaded(
    val quad: Quad<NamedGroupWire, GroupStatsOut, List<BalanceLineOut>, List<uniffi.skipti_core.ProjectedExpenseOut>>,
    val remaining: Int,
    val people: GroupPeople,
    val cardBalances: Map<String, String>,
    val historyNames: Map<String, String>,
    val progress: List<SettledProgressOut>,
    val milestone: Milestones.Shown?,
    /** Decision 122: a record of this group is parked. */
    val parked: Boolean,
)

@Composable
private fun Stat(label: String, value: String, colour: Color, dark: Boolean, modifier: Modifier) {
    Column(
        modifier
            .background(Tok.surface(dark).copy(alpha = 0.8f), ThemeArt.panelShape(Appearance.theme, 14))
            .padding(10.dp)
            // D-228 decision 43: the placeholder value (the Settled tile, a money fallback) is spoken as "none".
            .semantics(mergeDescendants = true) { contentDescription = "$label ${Placeholder.spoken(value)}" },
    ) {
        // Labels wrap rather than clip ("You'll get back" at three across);
        // the amount shrinks to fit (iOS: minimumScaleFactor 0.6) rather than lose its last digit.
        Text(label, style = Type.bodyDim(dark).copy(fontSize = 12.sp), maxLines = 2)
        // D-221 fix wave (S11): iOS's 2 between the label and the value.
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = Type.numericColored(TypeScale.statValue, colour),
            autoSize = amountAutoSize(TypeScale.statValue),
            maxLines = 1,
            softWrap = false,
            // Past the floor, iOS truncates the tail with an ellipsis (lineLimit 1); so does this.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** D-217 final fix: an amount drawn at [size] shrinks continuously (0.2 sp steps) down to [amountMinSize] to stay on
 *  one line, the twin of iOS's `.minimumScaleFactor(0.6)`. */
private fun amountAutoSize(size: Float) =
    TextAutoSize.StepBased(minFontSize = amountMinSize(size).sp, maxFontSize = size.sp, stepSize = 0.2.sp)
