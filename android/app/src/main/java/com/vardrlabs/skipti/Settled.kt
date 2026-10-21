package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.SettledProgressOut
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.ffiSettledProgress
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * D-221 (E4, L41 to L45): the group page's Settled card, its next payment, its milestones and when the celebration
 * plays. Money and the percent's basis points come from core (`ffiSettledProgress`, `ffiBalanceLine`); this file holds
 * the pure decisions and the copy, the twin of iOS's Settled.swift (SettledTest reads it).
 */
object SettledCopy {
    const val LABEL = "Settled"
    const val NOTHING_LEFT = "Nothing left to settle"
    const val HUNDRED = "100 expenses together"
    const val YEAR = "A year together"
    const val LAST_ONE = "The last one"
    const val SETTLE = "Settle"
    const val SETTLING = "Settling"

    /** [amount] is core's formatted money (`ffiMoneyFormat`). */
    fun stillToSettle(amount: String): String = "$amount still to settle"

    /** [date] is `AppDate.day` of the group's first expense. */
    fun since(date: String): String = "Since $date. Rare, and only once."

    fun paymentsToGo(n: Int): String = "$n payments to go"
}

object SettledSummary {
    /** One block per currency: the group's settlement currency first, then by code. */
    fun blocks(progress: List<SettledProgressOut>, settlementCurrency: String?): List<SettledProgressOut> =
        progress.sortedWith(compareBy<SettledProgressOut> { if (it.currencyCode == settlementCurrency) 0 else 1 }.thenBy { it.currencyCode })

    /** All settled: at least one block, and every block settled. */
    fun allSettled(progress: List<SettledProgressOut>): Boolean = progress.isNotEmpty() && progress.all { it.settled }

    /** The percent the card prints: core's basis points over 100, floored. */
    fun percent(basisPoints: UInt): Int = (basisPoints / 100u).toInt()
}

/** D-221 (L41, Q17 a): the group's next card: the viewer's largest open payment across the group's balance lines. */
object GroupNext {
    /** One entry of a viewer-framed balance line: negative, you pay them; positive, they pay you. */
    data class Pick(val currencyCode: String, val entry: ShareOut) {
        val youOwe: Boolean get() = entry.minorUnits < 0
        val amountMinor: Long get() = abs(entry.minorUnits)
    }

    /** The largest open entry by size; ties by currency code, then by id. */
    fun pick(lines: List<BalanceLineOut>): Pick? =
        lines.flatMap { line -> line.entries.filter { it.minorUnits != 0L }.map { Pick(line.currencyCode, it) } }
            .sortedWith(compareByDescending<Pick> { it.amountMinor }.thenBy { it.currencyCode }.thenBy { it.entry.id })
            .firstOrNull()

    /** D-221 fix wave (S2, WORKLOG decision 17): every payment still open in the group's PAIRWISE view (after core's
     *  loop cancelling), the one model Settle up's cards show and record. [frames] holds each person's own lines (core's
     *  `ffiBalanceLine` with that person as viewer); a debt shows in both people's frames with opposite signs, so each
     *  nonzero entry is counted once per (currency, the pair). The plan's transfers are never counted. The twin of iOS's
     *  `GroupNext.remaining(frames:)`. */
    fun remaining(frames: Map<String, List<BalanceLineOut>>): Int {
        val pairs = HashSet<Triple<String, String, String>>()
        for ((viewer, lines) in frames) {
            for (line in lines) {
                for (e in line.entries) {
                    if (e.minorUnits != 0L) pairs.add(Triple(line.currencyCode, minOf(viewer, e.id), maxOf(viewer, e.id)))
                }
            }
        }
        return pairs.size
    }

    /** The group's open payments, read through core once per distinct person: every member and every removed person
     *  (D-210 keeps one who still holds a balance in the pairwise view, so a debt between two removed people is still
     *  open). [linesOf] reads that person's frame. The twin of iOS's `remaining(members:removed:lines:)`. */
    fun remaining(members: List<String>, removed: List<String>, linesOf: (String) -> List<BalanceLineOut>): Int =
        remaining((members + removed).distinct().associateWith(linesOf))

    /** The muted line under the sentence: "The last one" exactly at one, else "N payments to go". */
    fun remainingLine(n: Int): String = if (n == 1) SettledCopy.LAST_ONE else SettledCopy.paymentsToGo(n)
}

/** D-221 fix wave (S1): the Settle tap's latch, the twin of iOS's `SettleLatch`. A tap holds the payment it records:
 *  "Settling" shows, and every other tap records nothing, this payment or another, until a successful reload no longer
 *  shows it ([moved]) or the record failed. One payment at a time: while one records, every other Settle button is
 *  disabled ([busy]). Compose state, so the views redraw when it changes; memory only. */
class SettleLatch<T> {
    var inFlight: T? by mutableStateOf(null)
        private set

    /** A payment is being recorded: every Settle button reads as disabled. */
    val busy: Boolean get() = inFlight != null

    /** A tap on [pick]: true, and the latch holds it, unless a payment is already being recorded. */
    fun begin(pick: T): Boolean {
        if (inFlight != null) return false
        inFlight = pick
        return true
    }

    fun settling(pick: T): Boolean = inFlight == pick

    /** The record failed: the payment can be tried again. */
    fun failed() {
        inFlight = null
    }

    /** A reload that succeeded now shows [shown]: once the payment in flight is not among them, the latch lets go (the
     *  record landed and the page moved on). */
    fun moved(shown: Collection<T>) {
        val held = inFlight
        if (held != null && held !in shown) inFlight = null
    }
}

/** The spec's two milestones, never about money. */
enum class Milestone { HUNDRED, YEAR }

/** One live expense of the group, as the milestones read it: when it was added, and its own date
 *  (`timestampMs ?: addedAtMs`). */
data class MilestoneExpense(val addedAtMs: Long, val dateMs: Long)

/** D-221 (L44): "100 expenses together" is reached when the group's 100th live expense was added; "A year together"
 *  365 days after its first expense's own date. A milestone shows for [WINDOW_MS] from the moment it was reached (end
 *  exclusive), never before; when both show, the later reached wins. Goldens: .drafts/d221/milestones.out. */
object Milestones {
    const val DAY_MS = 86_400_000L
    const val WINDOW_MS = 14 * DAY_MS
    const val YEAR_MS = 365 * DAY_MS
    const val HUNDRED_AT = 100

    /** The milestone shown, when it was reached, and the group's first expense's date (the card's "Since"). */
    data class Shown(val milestone: Milestone, val reachedMs: Long, val sinceMs: Long)

    fun current(expenses: List<MilestoneExpense>, nowMs: Long): Shown? {
        if (expenses.isEmpty()) return null
        val since = expenses.minOf { it.dateMs }
        val reached = buildList {
            val added = expenses.map { it.addedAtMs }.sorted()
            if (added.size >= HUNDRED_AT) add(Milestone.HUNDRED to added[HUNDRED_AT - 1])
            add(Milestone.YEAR to since + YEAR_MS)
        }
        return reached.filter { (_, at) -> at <= nowMs && nowMs < at + WINDOW_MS }
            .maxByOrNull { it.second }
            ?.let { (milestone, at) -> Shown(milestone, at, since) }
    }
}

/** D-221 (L45): the settled celebration plays when a group the page has already loaded goes from not all settled to
 *  all settled (never on the first load, never in a read-only copy); its burst only outside still mode. */
object Celebration {
    fun plays(before: Boolean?, after: Boolean, isCopy: Boolean): Boolean = before == false && after && !isCopy

    /** The particles fly unless "remove animations" or Less motion holds them. D-239 (decision 137): the celebration
     *  is a moment, so power saving never stills it. */
    fun showsBurst(reduceMotion: Boolean): Boolean = !reduceMotion

    /** What TalkBack hears, once per play: the two lines (the twin of iOS's `Celebration.announcement`). */
    fun announcement(group: String): String = "${SettleAllCopy.settled(group)}. ${SettleAllCopy.SETTLED_DETAIL}"
}

/** D-221 fix wave (S4, SPEC 11.7): what a screen reader hears for the new money views, with core's spoken money
 *  (`ffiMoneySpoken`), never the formatted string. */
object SettledSpeech {
    /** One Settled block: its label ("Settled", or the currency's code), core's percent, and what is left. */
    fun block(label: String, percent: Int, spokenLeft: String?): String =
        "$label, $percent%, " + (spokenLeft?.let { SettledCopy.stillToSettle(it) } ?: SettledCopy.NOTHING_LEFT)

    /** The next card's words: the payment with its spoken amount, what is left, and section 11.6's line. */
    fun next(sentence: String, spoken: String, remainingLine: String): String =
        "$sentence $spoken. $remainingLine. ${SettleAllCopy.EXPLAINER}"
}

/** D-221 (Design 1, 11): what the group page reads for its Settled card and its milestone, off the main thread. */
object GroupProgress {
    /** D-224 fix wave (F7): whether the group is all settled now, by the page's own rule ([SettledSummary.allSettled]). */
    fun allSettled(store: LedgerStore, groupId: String): Boolean = SettledSummary.allSettled(progress(store, groupId))

    /** Core's settled progress per currency of the group (integer money; the shells never add it). */
    fun progress(store: LedgerStore, groupId: String): List<SettledProgressOut> =
        ffiSettledProgress(
            store.loadExpenses().map { it.toInput() }, store.loadSettlements().map { it.toInput() },
            store.loadRedistributions(), groupId,
        )

    /** The milestone shown now, over the group's live expenses (core's projection: edits applied, deletions out). */
    fun milestone(store: LedgerStore, groupId: String, nowMs: Long): Milestones.Shown? =
        Milestones.current(
            store.projectedExpenses().expenses.filter { !it.deleted && it.expense.group == groupId }
                .map { MilestoneExpense(it.addedAtMs.toLong(), (it.timestampMs ?: it.addedAtMs).toLong()) },
            nowMs,
        )
}

/** D-221 (L42): the settled meter's two heights (art.py 19; Vardr Labs draws thinner), and its fill as a fraction of
 *  core's basis points (a drawing ratio, never money). */
object SettledMeters {
    fun height(theme: AppTheme, big: Boolean): Float = when {
        big && theme == AppTheme.VARDR -> ThemeArt.m("meterBigVardr")
        big -> ThemeArt.m("meterBig")
        theme == AppTheme.VARDR -> ThemeArt.m("meterSmallVardr")
        else -> ThemeArt.m("meterSmall")
    }

    fun fraction(basisPoints: UInt): Float = minOf(basisPoints, 10_000u).toFloat() / 10_000f
}

/** D-221 (L44): the seal's number and the milestone's title. */
object MilestoneWords {
    fun number(m: Milestone): String = when (m) {
        Milestone.HUNDRED -> "100"
        Milestone.YEAR -> "1"
    }

    fun title(m: Milestone): String = when (m) {
        Milestone.HUNDRED -> SettledCopy.HUNDRED
        Milestone.YEAR -> SettledCopy.YEAR
    }
}

/** D-221 (L42): the settled meter: the track in `ringTrack` (Norse's segmented, `meterSegment` of track and `meterGap`
 *  of `norseMeterGap`, repeating from the left), the fill the meter gradient across the filled width, both in C2's
 *  meter shape (Aurora a capsule, Norse and Vardr Labs square). Decorative: the percent carries it. The twin of iOS's
 *  `SettledMeter`. */
@Composable
fun SettledMeter(fraction: Float, big: Boolean, dark: Boolean, modifier: Modifier = Modifier) {
    val theme = Appearance.theme
    val shape = ThemeArt.meterShape(Appearance.theme)
    val track = Tok.ringTrack(dark)
    val gap = ThemeArt.color("norseMeterGap")
    Box(
        modifier
            .fillMaxWidth()
            .height(SettledMeters.height(theme, big).dp)
            .clip(shape)
            .drawBehind {
                if (theme == AppTheme.NORSE) {
                    drawRect(gap)
                    val segment = ThemeArt.m("meterSegment").dp.toPx()
                    val step = segment + ThemeArt.m("meterGap").dp.toPx()
                    // D-221 fix wave (S11): whole segments only, the twin of iOS's (no clipped last one).
                    var x = 0f
                    while (x + segment <= size.width + 0.01f) {
                        drawRect(track, topLeft = Offset(x, 0f), size = Size(segment, size.height))
                        x += step
                    }
                } else {
                    drawRect(track)
                }
            }
            .clearAndSetSemantics {},
    ) {
        if (fraction > 0f) Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(Tok.meterStart(dark), Tok.meterEnd(dark)))),
        )
    }
}

/** D-221 (L43, Design 2, 6): the group's Settled card: "Settled" (Norse's mark) and core's percent on one baseline, the
 *  big meter, then what is still to settle in core's money. One block per currency (the settlement currency first);
 *  with more than one, each block's row names its currency in the kicker face. The twin of iOS's `SettledCard`. */
@Composable
fun SettledCard(blocks: List<SettledProgressOut>, dark: Boolean) {
    val many = blocks.size > 1
    Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (many) SectionLabel(SettledCopy.LABEL, dark, color = Tok.text(dark))
        blocks.forEach { b ->
            val percent = "${SettledSummary.percent(b.basisPoints)}%"
            // S4: one element per block, spoken with core's spoken money (SPEC 11.7).
            val spoken = SettledSpeech.block(
                if (many) b.currencyCode else SettledCopy.LABEL, SettledSummary.percent(b.basisPoints),
                if (b.settled) null else BalanceUi.moneySpoken(b.outstandingMinor, b.currencyCode, AppLocale.current),
            )
            Column(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    if (many) {
                        Text(Type.kicker(b.currencyCode), style = Type.microLabel(dark), modifier = Modifier.alignByBaseline())
                    } else {
                        SectionLabel(SettledCopy.LABEL, dark, modifier = Modifier.alignByBaseline(), color = Tok.text(dark))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        percent,
                        style = Type.numeric(TypeScale.settledPercent, FontWeight.Bold, dark),
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                SettledMeter(SettledMeters.fraction(b.basisPoints), big = true, dark)
                Text(
                    if (b.settled) SettledCopy.NOTHING_LEFT
                    else SettledCopy.stillToSettle(BalanceUi.moneyFormat(b.outstandingMinor, b.currencyCode, AppLocale.current)),
                    style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
                )
            }
        }
    }
}

/** D-221 (L44, Design 11): the milestone card: the theme's seal (the tile's own shape, drawn side and edge, at `seal`)
 *  with the number in the accent, the title, and "Since <the first expense's date>. Rare, and only once." The twin of
 *  iOS's `MilestoneCard`. */
@Composable
fun MilestoneCard(shown: Milestones.Shown, dark: Boolean) {
    val kind = SealStyle.clip(Appearance.theme)
    val side = ThemeArt.m("seal").toInt()
    val shape = tileShape(kind, side)
    Row(
        Modifier
            .glassCard(dark)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // S5: laid out by its drawn side (Norse's diamond is side x sqrt 2), so its tips never crowd the title.
        Box(Modifier.size(tileDrawnSide(kind, side).dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .requiredSize(tileDrawnSide(kind, side).dp)
                    .tileEdge(kind, side)
                    .clip(shape)
                    .background(Tok.accentSoft(dark), shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    MilestoneWords.number(shown.milestone),
                    style = Type.numericColored(TypeScale.sealNumber, Tok.accent(dark), FontWeight.Bold),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // S11: iOS's 2 between the title and its line.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(MilestoneWords.title(shown.milestone), style = Type.name(dark, 16))
            Text(
                SettledCopy.since(ExpenseListUi.dateString(shown.sinceMs, AppLocale.current)),
                style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
            )
        }
    }
}

/** D-221 (L41, Q17 a, Design 8): the group's next card: the viewer's largest open payment ("Sam pays you ₹560.00"),
 *  the muted count of what is left, §11.6's sentence, and "Settle" (Settling while it records). It records through
 *  Settle up's own function ([SettleAll.record]), then the page reloads ([SyncStatus.changed]). Never shown for a
 *  read-only copy (the page decides). The twin of iOS's `GroupNextCard`. */
@Composable
fun GroupNextCard(
    vaultPath: String,
    named: NamedGroupWire,
    history: Map<String, String>,
    pick: GroupNext.Pick,
    remaining: Int,
    dark: Boolean,
    onError: (String?) -> Unit,
    onAllSettled: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // S1: held from the tap until the page moves on; a card for another payment starts with a fresh latch (M3).
    val latch = remember(pick) { SettleLatch<GroupNext.Pick>() }
    val settling = latch.settling(pick)
    val other = LedgerStore.label(pick.entry.id, named, history)
    val amount = BalanceUi.moneyFormat(pick.amountMinor, pick.currencyCode, AppLocale.current)
    val spoken = BalanceUi.moneySpoken(pick.amountMinor, pick.currencyCode, AppLocale.current)
    val stacked = TextSize.stacks(LocalDensity.current.fontScale)
    val action: @Composable (Modifier) -> Unit = { m ->
        PrimaryAction(
            if (settling) SettledCopy.SETTLING else SettledCopy.SETTLE, dark, enabled = !settling, compact = true, modifier = m,
        ) {
            if (!latch.begin(pick)) return@PrimaryAction
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { SettleAll.record(vaultPath, pick.entry, pick.currencyCode, named, askSettled = true) }
                }
                val failure = outcome.exceptionOrNull()
                if (failure == null) {
                    // The latch keeps "Settling" until the reloaded page picks another payment.
                    onError(null)
                    // D-224 fix wave (F7): the payment settled the group: its toast gives way and the celebration is
                    // marked now (iOS's start), before the page's own read, which takes about a second here. Posted to
                    // the main looper like the record's own toast, so it runs after that post, never before it.
                    val settled = outcome.getOrDefault(false)
                    if (settled) MainThread.executor.execute {
                        DoneCenter.clear()
                        onAllSettled()
                    }
                    SyncStatus.changed()
                } else {
                    latch.failed()
                    onError("Could not record the settlement. ${failure.message ?: failure}")
                }
            }
        }
    }
    val words: @Composable (Modifier) -> Unit = { m ->
        // S4: the words as one element with core's spoken money; the button stays its own. S11: iOS's 4 between lines.
        Column(
            m.clearAndSetSemantics {
                contentDescription = SettledSpeech.next(SettleAllCopy.sentence(pick.youOwe, other), spoken, GroupNext.remainingLine(remaining))
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${SettleAllCopy.sentence(pick.youOwe, other)} $amount", style = Type.name(dark, 16))
            Text(GroupNext.remainingLine(remaining), style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            // §11.6, verbatim: the line SKIPTI prints wherever a payment is recorded.
            Text(SettleAllCopy.EXPLAINER, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
        }
    }
    if (stacked) {
        Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            words(Modifier)
            action(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.glassCard(dark), verticalAlignment = Alignment.CenterVertically) {
            words(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            action(Modifier)
        }
    }
}

/** D-221 (Design 12, motion.py part 5): where the celebration is [elapsed] seconds in. Moving: the page's content dims
 *  to [CelebrationTiming.DIM_TO] over [CelebrationTiming.DIM_IN] and comes back over the last DIM_IN; the words fade
 *  in over [CelebrationTiming.MESSAGE_IN] rising [CelebrationTiming.MESSAGE_RISE] points and undo the same way over the
 *  last MESSAGE_IN, all linear. Still (Reduce Motion or Less motion; D-239: never battery saver): dimmed and shown at once, without motion, for
 *  [CelebrationTiming.TOTAL]. */
object CelebrationTimeline {
    /** The words' opacity and how far below their place they are, in points. */
    data class MessagePose(val alpha: Float, val rise: Float)

    /** The alpha of the page's content (1 is undimmed). */
    fun dim(elapsed: Double, still: Boolean): Float {
        val to = CelebrationTiming.DIM_TO
        val total = CelebrationTiming.TOTAL
        if (elapsed >= total) return 1f
        if (still) return to.toFloat()
        val ramp = minOf(elapsed, total - elapsed).coerceAtLeast(0.0) / CelebrationTiming.DIM_IN
        return (1 - (1 - to) * ramp.coerceAtMost(1.0)).toFloat()
    }

    fun message(elapsed: Double, still: Boolean): MessagePose {
        val total = CelebrationTiming.TOTAL
        if (still) return if (elapsed < total) MessagePose(1f, 0f) else MessagePose(0f, 0f)
        val shown = (minOf(elapsed, total - elapsed).coerceAtLeast(0.0) / CelebrationTiming.MESSAGE_IN).coerceAtMost(1.0)
        return MessagePose(shown.toFloat(), (CelebrationTiming.MESSAGE_RISE * (1 - shown)).toFloat())
    }
}

/** D-221 (Design 12): what the group's page remembers between its loads, held by the shell for as long as that group
 *  stays open (over a pushed page too, as iOS's page keeps its state in its NavigationStack): whether the last load
 *  was all settled, and a play waiting until the page is seen. Memory only: nothing is stored. */
class CelebrationState {
    var before: Boolean? = null
    var pending by mutableStateOf(false)

    /** D-224 fix wave (F7): the page's own next card recorded the payment that settled the group: the play is marked
     *  now, as iOS's page (which reloads in the same turn) marks it, not after Android's second read of the whole page
     *  (about 1 s on the look's emulator); the page's state is all settled from here, so that read never plays it
     *  again. The same rule as a load's ([Celebration.plays]). */
    fun settledHere(isCopy: Boolean) {
        if (!Celebration.plays(before, after = true, isCopy = isCopy)) return
        pending = true
        before = true
    }
}

/** One play of the celebration: its clock. [still] plays without motion (no frames, a timer); otherwise the frame
 *  clock on the art's shared 30 fps grid, [startedAt] being the first frame's time in the clock `artCanvas` hands
 *  the burst. Ends at [CelebrationTiming.TOTAL]. */
class CelebrationPlay(val still: Boolean) {
    var startedAt by mutableDoubleStateOf(Double.NaN)
    var elapsed by mutableDoubleStateOf(0.0)

    suspend fun run() {
        if (still) {
            delay((CelebrationTiming.TOTAL * 1000).toLong())
            elapsed = CelebrationTiming.TOTAL
            return
        }
        val gate = FrameGate()
        while (elapsed < CelebrationTiming.TOTAL) {
            withFrameNanos { now ->
                val seconds = now / 1e9
                if (startedAt.isNaN()) startedAt = seconds
                if (gate.due(now)) elapsed = seconds - startedAt
            }
        }
    }
}

/** D-221 (L45): the settled celebration over the group's page: the burst through the motion engine (only when
 *  [Celebration.showsBurst]) and the two lines, centred at [CelebrationTiming.MESSAGE_TOP] of the page, a polite live
 *  region so TalkBack reads them. GUARANTEES #2: it never takes a touch (no pointer input, no click, no window of its
 *  own); the page dims its own content. Nothing is logged. The twin of iOS's `CelebrationOverlay`. */
@Composable
fun CelebrationOverlay(play: CelebrationPlay, groupName: String, dark: Boolean, motion: MotionState, onDone: () -> Unit) {
    val theme = Appearance.theme
    val ring = Tok.ring(dark)
    // S6: Norse's 24 runes measured once per theme and density (the twin of iOS's 24 symbols), never per frame.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val runes = remember(theme, density, measurer) { if (theme == AppTheme.NORSE) ArtDraw.burstRunes(measurer, density) else emptyList() }
    // S9: TalkBack hears the two lines once per play from a node that is never transparent: it starts empty and is
    // filled once the play has begun, a change of a live region's content, which TalkBack announces.
    var spoken by remember(play) { mutableStateOf("") }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(play) {
        spoken = Celebration.announcement(groupName)
        // D-224 (Design 9): a settle's toast posted a moment before gives way, and the celebration plays the success
        // (through the door's gate, so a settle that completes the group buzzes once).
        DoneCenter.clear()
        Haptics.play(view, HapticKind.SUCCESS)
        play.run()
        onDone()
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                },
        )
        if (Celebration.showsBurst(motion.reduce)) Box(
            Modifier.fillMaxSize().artCanvas(still = 0.0, state = motion.moment()) { t ->
                with(ArtDraw) { burst(t - play.startedAt, theme, ring, runes) }
            },
        )
        Layout(
            content = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .graphicsLayer {
                            val m = CelebrationTimeline.message(play.elapsed, play.still)
                            alpha = m.alpha
                            translationY = m.rise.dp.toPx()
                        }
                        // S9: the words on screen fade from 0, so they are not what TalkBack reads (the node below is).
                        .clearAndSetSemantics {}
                        // D-234 (L10, decision 95): on the theme's card, so the words never read on the page's own words.
                        .glassCard(dark),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        SettleAllCopy.settled(groupName),
                        style = Type.display(dark, 700).copy(fontSize = TypeScale.displayName.sp, color = Tok.text(dark)),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        SettleAllCopy.SETTLED_DETAIL,
                        style = Type.body(dark).copy(fontSize = TypeScale.detail.sp, color = Tok.text(dark)),
                        textAlign = TextAlign.Center,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val words = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                words.place(
                    (constraints.maxWidth - words.width) / 2,
                    (constraints.maxHeight * CelebrationTiming.MESSAGE_TOP - words.height / 2.0).roundToInt(),
                )
            }
        }
    }
}
