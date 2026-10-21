package com.vardrlabs.skipti

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.skipti_core.CurrencyAmountOut
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.PersonNetOut
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.SettledProgressOut
import java.time.ZoneId

/*
 * D-223 (Home: "Your ledgers" and "Lately", the Look Book's L52 to L54): the models, decisions and words Home draws,
 * pure and tested on the JVM; the twin of iOS's HomeParts.swift (HomePartsTest pins the same golden tables, retyped
 * from .drafts/d223/art.out, and compares the words with the Swift file's). Every amount string comes from core.
 */

/** The hero's who-owes line (Q4 a, LB 93): the first two people of the overview in the hero's currency, in core's
 *  order (largest first). A positive net is money coming to you. */
object WhoOwes {
    const val LIMIT = 2

    /** The line's parts, at most [LIMIT]; empty with no hero currency or nobody in it (the line is not drawn).
     *  [format] is core's money format of a magnitude in a currency. */
    fun parts(people: List<PersonNetOut>, currency: String?, format: (Long, String) -> String): List<String> {
        if (currency == null) return emptyList()
        val out = mutableListOf<String>()
        for (p in people) {
            if (p.currencyCode != currency || p.netMinor == 0L) continue
            val amount = format(Math.abs(p.netMinor), p.currencyCode)
            out += if (p.netMinor > 0) OverviewCopy.owesYou(p.name, amount) else OverviewCopy.youOwe(p.name, amount, first = out.isEmpty())
            if (out.size == LIMIT) break
        }
        return out
    }
}

/** When a change happened, in the words Lately and Recent activity print (one rule on both phones; art.out 35; DECIDED
 *  for the founder: the two system relative formatters word the same moment differently). A change a week old or more
 *  is [Date], printed as its day and month on AppLocale's axis ([words]). A negative delta reads as now. */
sealed class RelativeWhen {
    data object Now : RelativeWhen()
    data class Minutes(val n: Long) : RelativeWhen()
    data class Hours(val n: Long) : RelativeWhen()
    data class Days(val n: Long) : RelativeWhen()
    data object Date : RelativeWhen()

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
        private const val WEEK_DAYS = 7

        fun of(atMs: Long, nowMs: Long): RelativeWhen {
            val d = maxOf(nowMs - atMs, 0L)
            return when {
                d < MINUTE -> Now
                d < HOUR -> Minutes(d / MINUTE)
                d < DAY -> Hours(d / HOUR)
                d < WEEK_DAYS * DAY -> Days(d / DAY)
                else -> Date
            }
        }

        /** The words; null for [Date] (the day and month, [words]). */
        fun text(w: RelativeWhen): String? = when (w) {
            Now -> "now"
            is Minutes -> "${w.n} min"
            is Hours -> "${w.n} h"
            is Days -> if (w.n == 1L) "1 day" else "${w.n} days"
            Date -> null
        }

        /** What a row prints: the words, or the day and month for an older change (on AppLocale's axis). */
        fun words(
            atMs: Long,
            nowMs: Long,
            tag: LocaleTag = AppLocale.current,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String = text(of(atMs, nowMs)) ?: AppDate.dayMonth(atMs, tag, zone)
    }
}

/** A ledger card's line (L54): what the group's first net says, as a magnitude in its currency. */
sealed class LedgerLine {
    abstract val currency: String?

    /** D-250: core's net behind the line (owed to you positive, owed by you negative, settled 0), for its roll. */
    val amountMinor: Long
        get() = when (this) {
            is Owed -> minor
            is YouOwe -> -minor
            Settled -> 0L
        }

    /** Money coming to you ("Owed", in owing). */
    data class Owed(val minor: Long, override val currency: String) : LedgerLine()

    /** Money you owe ("You owe", in owed). */
    data class YouOwe(val minor: Long, override val currency: String) : LedgerLine()

    /** Nothing open ("Settled"). */
    data object Settled : LedgerLine() {
        override val currency: String? = null
    }
}

object LedgerCards {
    /** The first net only (core's order, largest first); none, or zero, is settled. */
    fun line(nets: List<CurrencyAmountOut>): LedgerLine {
        val first = nets.firstOrNull() ?: return LedgerLine.Settled
        return when {
            first.amountMinor > 0 -> LedgerLine.Owed(first.amountMinor, first.currencyCode)
            first.amountMinor < 0 -> LedgerLine.YouOwe(-first.amountMinor, first.currencyCode)
            else -> LedgerLine.Settled
        }
    }

    /** The card's meter: the block in the line's currency, else the first block (the settlement currency's, E4); none
     *  for a group with no blocks (no meter is drawn). */
    fun basisPoints(blocks: List<SettledProgressOut>, currency: String?): UInt? =
        (blocks.firstOrNull { it.currencyCode == currency } ?: blocks.firstOrNull())?.basisPoints

    /** What TalkBack hears for a card, one element: "Goa trip, Owed one thousand eight hundred rupees, 72% settled",
     *  with core's spoken money (SPEC section 11.7, D-221 S4); the twin of iOS's `LedgerCard.spoken`. */
    fun spoken(name: String, line: LedgerLine, basisPoints: UInt?): String {
        val parts = mutableListOf(name)
        parts += when (line) {
            is LedgerLine.Owed -> LedgerCopy.OWED + " " + RolesMoney.spoken(line.minor, line.currency)
            is LedgerLine.YouOwe -> LedgerCopy.YOU_OWE + " " + RolesMoney.spoken(line.minor, line.currency)
            LedgerLine.Settled -> OverviewCopy.SETTLED_SHORT
        }
        if (basisPoints != null) parts += LedgerCopy.settledPercent(SettledSummary.percent(basisPoints))
        return parts.joinToString(", ")
    }
}

/** The ledger grid (L52): two columns, one at accessibility text sizes; an odd last card keeps its one column. */
object LedgerGrid {
    fun columns(accessibilitySize: Boolean): Int = if (accessibilitySize) 1 else 2

    /** The cards' indices, row by row, in order. */
    fun rows(count: Int, columns: Int): List<List<Int>> = (0 until count).chunked(maxOf(columns, 1))
}

/** Home's Lately card (L53): the newest few of the activity feed, read only. */
object Lately {
    const val COUNT = 3

    fun pick(entries: List<ActivityFeed.Entry>): List<ActivityFeed.Entry> = entries.take(COUNT)
}

/** "Your ledgers" words; iOS's twin is `LedgerCopy`. */
object LedgerCopy {
    const val TITLE = "Your ledgers"
    const val OWED = "Owed"
    const val YOU_OWE = "You owe"
    /** The card's spoken percent, n = basis points / 100, floored. */
    fun settledPercent(n: Int) = "$n% settled"
    const val LOAD_ERROR = "Could not load the ledger"
}

/** Lately's words; iOS's twin is `LatelyCopy`. */
object LatelyCopy {
    const val TITLE = "Lately"
    /** A1: the whole-ledger Activity screen (orphaned since D-196) is reached from Lately's header. */
    const val SEE_ALL = "See all"
}

/** A2: Expenses' empty state is SPEC section 11.6's line verbatim, split into the drawn state's title and detail; iOS's
 *  twin is `EmptyCopy`. */
object EmptyCopy {
    const val EXPENSES_TITLE = "Nothing here yet"
    const val EXPENSES_DETAIL = "Add your first expense, or import from another app."
}

/** The who-owes line's parts; iOS's twins are `OverviewCopy.owesYou(_:_:)` and `.youOwe(_:_:first:)`. */
fun OverviewCopy.owesYou(name: String, amount: String): String {
    return "$name owes you $amount"
}

fun OverviewCopy.youOwe(name: String, amount: String, first: Boolean): String {
    return if (first) "You owe $name $amount" else "you owe $name $amount"
}

// ---- D-223 Task 4: the views (the twins of iOS's LedgerGrid, LedgerCard, LatelyCard, LatelyRow and EmptyState) ----

/** The ledger grid (L52, Design 3): [count] cards in [LedgerGrid.rows], two columns, one at accessibility text sizes
 *  (the app's switch, BalanceScreen's and SettleScreen's), `ledgerGridGap` between columns and rows. The cards of a row
 *  share its height: the row is its intrinsic minimum, each card fills it. An odd last card keeps its one column.
 *  [card] draws card [index] with the [Modifier] that places it (Design 9: any ordered list of cards). */
@Composable
fun LedgerGrid(count: Int, card: @Composable (index: Int, modifier: Modifier) -> Unit) {
    // D-223 fix wave (M4): the house's one stacking rule (iOS's accessibility sizes, Appearance.kt), as every card.
    val columns = LedgerGrid.columns(accessibilitySize = TextSize.stacks(LocalDensity.current.fontScale))
    val gap = ThemeArt.m("ledgerGridGap").dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        LedgerGrid.rows(count, columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { i -> card(i, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One group's card on Home: its tile at `ledgerTile`, its name, the line from its first net and the meter from its
 *  settled blocks (core's, read once with Home). It opens the group's page. */
@Composable
fun GroupLedgerCard(
    named: NamedGroupWire,
    nets: List<CurrencyAmountOut>,
    blocks: List<SettledProgressOut>,
    dark: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = LedgerCards.line(nets)
    LedgerCard(named.group.name, line, LedgerCards.basisPoints(blocks, line.currency), dark, onOpen, modifier) {
        GroupTile(named.group, dark, ThemeArt.m("ledgerTile").toInt())
    }
}

/** The ledger card (L54, Design 4): the glass card at its default padding, the whole card a tap that opens the
 *  ledger; inside, `ledgerCardGap` apart, the tile and name in a row (`ledgerTileGap`), the line (at most two lines)
 *  and the small settled meter when there is one. One TalkBack element: "Goa trip, Owed ₹1,800.00, 72% settled". The
 *  tile is a slot (Design 9: a later card brings its own). */
@Composable
fun LedgerCard(
    name: String,
    line: LedgerLine,
    basisPoints: UInt?,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tile: @Composable () -> Unit,
) {
    val spoken = LedgerCards.spoken(name, line, basisPoints)
    Column(
        modifier
            // D-243 (T7): the ripple's clip below would cut the Norse card's edge glow (drawn outside the card), so the
            // glow is drawn here, before it; glassCard's own, inside the clip, draws nothing visible.
            .then(if (CardStyle.of(Appearance.theme) == CardStyle.NOTCHED) Modifier.norseEdgeGlow(Tok.accent(dark), ThemeArt.m("chamfer")) else Modifier)
            .clip(ThemeArt.cardShape(Appearance.theme))
            .clickable(onClickLabel = GroupsCopy.OPEN_HINT, onClick = onClick)
            .glassCard(dark)
            // One element; the tap and its hint stay the clickable's (outside, so not cleared).
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(ThemeArt.m("ledgerCardGap").dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            tile()
            Spacer(Modifier.width(ThemeArt.m("ledgerTileGap").dp))
            Text(
                name.withRunes(), style = Type.name(dark, TypeScale.ledgerName.toInt()),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // D-250: the line's amount rolls when it changes (core's integer behind it), the small meter sweeps.
        MoneyText(ledgerLineText(line, dark), line.amountMinor, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meter = rememberFill(basisPoints?.let { SettledMeters.fraction(it).toDouble() })
        basisPoints?.let { SettledMeter(meter, big = false, dark) }
    }
}

/** The card's line: "Owed " in textDim then the amount in owing (money coming to you), "You owe " then the amount in
 *  owed, or "Settled"; the amount in the numbers face at 600, its words from core. */
private fun ledgerLineText(line: LedgerLine, dark: Boolean): AnnotatedString = buildAnnotatedString {
    val dim = SpanStyle(color = Tok.textDim(dark))
    fun amount(minor: Long, code: String, colour: androidx.compose.ui.graphics.Color) {
        withStyle(Type.numericColored(TypeScale.detail, colour, FontWeight.SemiBold).toSpanStyle()) {
            append(RolesMoney.format(minor, code) ?: "")
        }
    }
    when (line) {
        is LedgerLine.Owed -> {
            withStyle(dim) { append(LedgerCopy.OWED + " ") }
            amount(line.minor, line.currency, Tok.owing(dark))
        }
        is LedgerLine.YouOwe -> {
            withStyle(dim) { append(LedgerCopy.YOU_OWE + " ") }
            amount(line.minor, line.currency, Tok.owed(dark))
        }
        LedgerLine.Settled -> withStyle(dim) { append(OverviewCopy.SETTLED_SHORT) }
    }
}

/** The Lately card (L53, Design 5, A1): the label and "See all" (the whole ledger's Activity) inside the card, then
 *  the newest [Lately.COUNT] entries `latelyRowGap` apart, with no dividers. Home shows it only when it has entries;
 *  read only, Home never marks the feed seen. */
@Composable
fun LatelyCard(
    loaded: ActivityFeed.Loaded,
    dark: Boolean,
    onOpenExpense: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val now = System.currentTimeMillis()
    Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(ThemeArt.m("latelyRowGap").dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(LatelyCopy.TITLE, dark, color = Tok.text(dark))
            Spacer(Modifier.weight(1f))
            // The card's link colour (accent, decision 21: it sits on the card, as "People in X"), 44 tall as iOS's;
            // Compose widens the touch area to 48 dp around it.
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onSeeAll),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(LatelyCopy.SEE_ALL, style = Type.body(dark).copy(fontSize = TypeScale.detail.sp, color = Tok.accent(dark)))
            }
        }
        Lately.pick(loaded.entries).forEach { entry -> LatelyRow(entry, loaded, now, dark, onOpenExpense) }
    }
}

/** One Lately row: the person's soft initials avatar (the theme's person shape), then the feed's line and the meta
 *  line (group, amount, when). An expense that is not deleted, in a group this phone knows, opens. */
@Composable
private fun LatelyRow(
    entry: ActivityFeed.Entry,
    loaded: ActivityFeed.Loaded,
    nowMs: Long,
    dark: Boolean,
    onOpenExpense: (String) -> Unit,
) {
    val item = entry.item
    val moved = entry.moved
    val named = loaded.groups[item?.group ?: moved?.group]
    val who = when {
        item != null -> ActivityFeed.who(item, loaded.groups, loaded.names)
        moved != null -> loaded.names[moved.group]?.get(moved.from) ?: LedgerStore.UNKNOWN_NAME
        else -> return
    }
    val first = if (item != null) ActivityFeed.line(item.kind, who, item.title) else moved?.let { loaded.movedLines[it.id] }.orEmpty()
    val amount = item?.let { RolesMoney.format(it.amountMinor, it.currency) }
    val meta = listOfNotNull(named?.group?.name, amount, RelativeWhen.words(entry.atMs, nowMs)).filter { it.isNotEmpty() }
    val openable = item != null && item.kind != ActivityFeed.Kind.DELETED && named != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable(onClickLabel = OverviewCopy.OPEN_EXPENSE) { onOpenExpense(item.expenseId) } else Modifier)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonAvatar(who, dark, ThemeArt.m("latelyAvatar").toInt(), fill = AvatarFill.SOFT)
        Spacer(Modifier.width(ThemeArt.m("latelyAvatarGap").dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                first.withRunes(),
                style = Type.name(dark, TypeScale.feedLine.toInt()).let {
                    if (item?.kind == ActivityFeed.Kind.DELETED) it.copy(color = Tok.textDim(dark)) else it
                },
            )
            Text(Separator.annotated(meta), style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
        }
    }
}

/** The drawn empty state (L-D217-10, Design 7): Lucide's inbox at `emptyIcon` in textDim, 12 under it the title in the
 *  name role at `emptyTitle`, 4 under that the detail in the body face, dim; centred, padding 16. Expenses' empty list
 *  and its load error both use it. D-223 fix wave (F3): the sides' 16 is the tab root's (iOS's state pads 16 at full
 *  width), so the state pads only above and below; 32 a side wrapped the detail with "app." alone. */
@Composable
fun EmptyState(title: String, detail: String, dark: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = TabIcons.Inbox,
            contentDescription = null,
            tint = Tok.textDim(dark),
            modifier = Modifier.size(ThemeArt.m("emptyIcon").dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = Type.name(dark, TypeScale.emptyTitle.toInt()), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = Type.bodyDim(dark), textAlign = TextAlign.Center)
    }
}
