package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.SettledProgressOut
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiCheckSettlement
import uniffi.skipti_core.ffiSettledProgress
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

    /** D-247 (decision 168, D-221 a): you owe nobody and nobody owes you, but others in the group still owe each other. */
    fun youSettled(group: String) = "You're settled in $group"
    const val YOU_SETTLED_DETAIL = "You don't owe anything here."

    /** D-247 (decision 167): a card's other amount, its sheet, and the cap (decision 183: never more than the debt). */
    const val ANOTHER_AMOUNT = "Another amount"
    const val HOW_MUCH_PAID = "How much was paid?"
    const val SETTLE = "Settle"
    const val CANCEL = "Cancel"

    /** When they owe you: "That is more than Ana owes."; when you owe them: "That is more than you owe Ana." */
    fun moreThanDebt(youOwe: Boolean, other: String) =
        if (youOwe) "That is more than you owe $other." else "That is more than $other owes."
    fun recorded(other: String) = "Settled with $other. Recorded in your ledger. No money moved."
}

/** D-182: one group's part of Settle up, framed by that group's own self. D-247: [progress], core's settled progress for
 *  the whole group (`ffiSettledProgress`), what says whether everybody is square; [history], every name the group's
 *  history keeps (a removed person's, for the toast). */
data class SettleSection(
    val named: NamedGroupWire,
    val lines: List<BalanceLineOut>,
    val progress: List<SettledProgressOut> = emptyList(),
    val history: Map<String, String> = emptyMap(),
) {
    /** Nobody in the group owes you, and you owe nobody in it. */
    val settled: Boolean get() = lines.all { it.entries.isEmpty() }

    val state: SettleState get() = SettleState.of(youSquare = settled, progress = progress)

    /** D-221 fix wave (S1): each card's payment, as the latch holds it. */
    val keys: List<SettleKey> get() = lines.flatMap { line -> line.entries.map { SettleKey(named.group.id, line.currencyCode, it) } }
}

/** D-247 (decision 168, D-221 a): what a group's section says. "All settled" only when core says the whole group is;
 *  you square while others still owe each other is its own words. The twin of iOS's `SettleState`. */
enum class SettleState {
    /** You owe someone here, or someone owes you: the cards. */
    OPEN,

    /** Core's `allSettled` (every currency's block settled), or a group with nothing in it yet (no block: nobody owes). */
    ALL_SETTLED,

    /** You are square, others are not: "You're settled in {group}". */
    YOU_SETTLED,
    ;

    companion object {
        fun of(youSquare: Boolean, progress: List<SettledProgressOut>): SettleState = when {
            !youSquare -> OPEN
            progress.isEmpty() || SettledSummary.allSettled(progress) -> ALL_SETTLED
            else -> YOU_SETTLED
        }
    }
}

/** D-221 fix wave (S1): one Settle up card's payment (its group, currency and entry, amount included). */
data class SettleKey(val group: String, val currency: String, val entry: ShareOut)

object SettleAll {
    /** Every group, each with core's lines for this device's self in it.
     *  The same read the screen makes; iOS's twin is `SettleAll.sections`. D-247: [held], a settle waiting out its Undo
     *  window ([SettleHold]), counts as recorded, so the screen shows what the write will leave (core's sums, never the
     *  shell's). A screen reads it under the hold's lock ([SettleHold.counting]). */
    fun sections(store: LedgerStore, held: List<SettlementDTO> = emptyList()): List<SettleSection> {
        // D-210: a read-only copy has nothing to settle in (nothing can be recorded in it).
        val copies = store.readOnlyChecked().keys
        val named = store.namedGroups().filter { it.group.id !in copies }
        val expenses = store.loadExpenses().map { it.toInput() }
        val settlements = (store.loadSettlements() + held).map { it.toInput() }
        val redistributions = store.loadRedistributions()
        val removed = store.removedPeople()
        return named.map {
            SettleSection(
                it,
                ffiBalanceLine(expenses, settlements, redistributions, removed[it.group.id].orEmpty().map { m -> m.id }, it.selfId, it.group.id),
                ffiSettledProgress(expenses, settlements, redistributions, it.group.id),
                runCatching { store.historyNames(it.group.id) }.getOrDefault(emptyMap()),
            )
        }
    }

    /** D-221 (Q17 a), D-247: records one payment: section 5.8's settlement record in the ledger (never a flag, never
     *  money). The ONE append: [SettleHold] writes through it when a settle's Undo window ends (Settle up's cards, their
     *  other amounts and the group page's next card). Core checks it first (`appendSettlement`, decision 103). Blocking.
     *  The twin of iOS's `SettleAll.record`. */
    fun record(store: LedgerStore, settlement: SettlementDTO) {
        store.appendSettlement(settlement)
    }

    /** A whole card's debt, written at once (no hold): a settlement filed under its group, framed by its own self. */
    fun append(store: LedgerStore, entry: ShareOut, currency: String, named: NamedGroupWire) =
        record(store, settlement(entry, currency, named))

    /** The settlement a card records: between the group's self and the other person, in the direction the viewer-framed
     *  entry says (negative means you owe them). D-247: [amount], another amount ([OtherAmount.verdict] keeps it within
     *  the debt); null is the whole debt. The twin of iOS's `SettleAll.settlement(for:currency:in:amount:)`. */
    fun settlement(entry: ShareOut, currency: String, named: NamedGroupWire, amount: Long? = null): SettlementDTO {
        val whole = SettleUi.settlementFor(entry, currency, named.group.id, named.selfId)
        return if (amount == null) whole else whole.copy(amountMinor = amount)
    }

    /** D-247 fix wave (review M3): the card a sheet's other amount is for, as the screen reads it NOW: the same group,
     *  currency and person, owing the same way. Null when that card is gone (settled meanwhile, or the debt turned the
     *  other way). The twin of iOS's `SettleAll.liveEntry`. */
    fun liveEntry(sections: List<SettleSection>, group: String, currency: String, person: String, youOwe: Boolean): ShareOut? =
        sections.firstOrNull { it.named.group.id == group }?.lines?.firstOrNull { it.currencyCode == currency }
            ?.entries?.firstOrNull { it.id == person && (it.minorUnits < 0) == youOwe && it.minorUnits != 0L }
}

/** D-247 (decisions 167, 183): a card's "Another amount": what the field starts with and what it may hold. Every amount
 *  is core's: the field is parsed by `ffiMoneyParseTyped` (D-248: a decimal comma reads) and checked by `ffiCheckSettlement`; the shell only compares
 *  two of core's minor-unit numbers (never adds or subtracts them). The twin of iOS's `OtherAmount`. */
object OtherAmount {
    /** What the field says about itself. */
    sealed interface Verdict {
        /** Settle stays disabled, with nothing said: empty, zero, or not an amount yet. */
        data object Incomplete : Verdict

        /** More than the debt: refused in words, Settle disabled (a partial never flips who owes whom). */
        data class TooMuch(val words: String) : Verdict

        /** D-248 (U30.2): something typed that is not an amount: `AmountEntry`'s words, said at a Settle tap or once the
         *  field has rested ([WORDS_PAUSE_MS]), never on the first keystroke (fix wave, review M1); nothing is recorded. */
        data class NotAnAmount(val words: String) : Verdict

        /** Settle records this many minor units. */
        data class Settles(val minor: Long) : Verdict
    }

    /** D-248 fix wave (review M1): how long the field rests before its not-an-amount words show, ms (a lone '.' or ','
     *  is the start of "0,5", not a mistake). The twin of iOS's `OtherAmount.wordsPause`. */
    const val WORDS_PAUSE_MS = 1_000L

    /** The whole debt as the field takes it back (core's entry form in the phone's mark: "450.00", "450,00" on a ','
     *  phone; no symbol, no grouping). */
    fun prefill(debt: Long, currency: String, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        AmountEntry.digits(debt, currency, locale) ?: ""

    /** D-247 fix wave (review M3): the cap again at the Settle tap, against the card's LIVE debt ([live], from
     *  [SettleAll.liveEntry]; null, the card has gone): a sync that shrank the debt while the sheet was open refuses in
     *  the cap's words, so a partial never flips who owes whom (decision 183). The twin of iOS's `OtherAmount.atSettle`. */
    fun atSettle(minor: Long, live: ShareOut?, youOwe: Boolean, other: String): Verdict =
        if (live == null || minor > abs(live.minorUnits)) Verdict.TooMuch(SettleAllCopy.moreThanDebt(youOwe, other))
        else Verdict.Settles(minor)

    /** The sheet's cap line: the field's own (more than the debt the sheet opened on; not an amount, once [rested]),
     *  else the tap's refusal. */
    fun capWords(verdict: Verdict, refused: String?, rested: Boolean): String? = when {
        verdict is Verdict.TooMuch -> verdict.words
        verdict is Verdict.NotAnAmount && rested -> verdict.words
        else -> refused
    }

    /** Settle can be tapped for an amount it settles, and for one that is not an amount (the tap says why, at once). */
    fun tappable(verdict: Verdict): Boolean = verdict is Verdict.NotAnAmount || settles(verdict)

    /** [text] against the debt ([debt], the card's amount, always above 0). [check] is core's settlement check of the
     *  amount (`SettleAll.settlement(..., amount)`); a refusal reads as incomplete. */
    fun verdict(
        text: String,
        debt: Long,
        currency: String,
        youOwe: Boolean,
        other: String,
        locale: java.util.Locale = java.util.Locale.getDefault(),
        check: (Long) -> Unit,
    ): Verdict {
        if (AmountEntry.isBlank(text)) return Verdict.Incomplete
        val minor = AmountEntry.minor(text, currency, locale) ?: return Verdict.NotAnAmount(AmountEntry.likeWords(currency, locale))
        if (minor <= 0) return Verdict.Incomplete
        if (minor > debt) return Verdict.TooMuch(SettleAllCopy.moreThanDebt(youOwe, other))
        if (runCatching { check(minor) }.isFailure) return Verdict.Incomplete
        return Verdict.Settles(minor)
    }

    fun settles(verdict: Verdict): Boolean = verdict is Verdict.Settles
}

/** D-247: the card a sheet's other amount is for (the sheet's subject). The twin of iOS's `OtherAmountDraft`. */
data class OtherAmountDraft(
    val key: SettleKey,
    val entry: ShareOut,
    val currency: String,
    val named: NamedGroupWire,
    val other: String,
    val history: Map<String, String> = emptyMap(),
) {
    val youOwe: Boolean get() = entry.minorUnits < 0
    val debt: Long get() = abs(entry.minorUnits)
}

/**
 * D-247 (decision 182, calls.md U29.1 route 3): a settle is held for [seconds] before anything is written, so Undo can
 * take it back with nothing to reverse. One at a time. It lands ([commit]) when its time is up, at the next Settle tap
 * (any card, any screen), when Settle up or the group page goes away ([LandsHeldSettle]), or when SKIPTI leaves the
 * front (MainActivity's onStop). While held, the screens count it as recorded ([held]), so the card reads settled at
 * once and a partial leaves its remainder. Every call is on the main thread (the write too, as iOS's: an app leaving
 * the front lands it before onStop returns); the screens read the vault's settlements and [held] together under [lock]
 * ([counting]), and [commit] writes and drops the held one under the same lock, so no read ever counts it twice or
 * not at all. The twin of iOS's `SettleHold`.
 */
class SettleHold(
    private val center: DoneMomentCenter = DoneCenter,
    /** The window in seconds; null (the app) reads it per hold from [window]. */
    private val seconds: Double? = null,
    /** Whether a screen reader (TalkBack) runs now (decision 201). */
    private val screenReader: () -> Boolean = ScreenReader::running,
    private val write: (String, SettlementDTO) -> Unit = { path, settlement -> SettleAll.record(LedgerStore(path), settlement) },
    private val written: (String) -> Unit = ::liveWritten,
    /** The window's clock (the main thread's, in the app). */
    private val scope: CoroutineScope = MainScope(),
    /** When a toast is posted (uptime ms, in the app). */
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    data class Pending(val id: Int, val settlement: SettlementDTO, val other: String, val path: String)

    private val lock = Any()

    /** The settle waiting out its window, if any. */
    @Volatile var pending: Pending? = null
        private set

    /** Goes up whenever what the screens count changes (held, undone, written, failed): they read again. */
    var revision by mutableIntStateOf(0)
        private set

    /** The last write that failed, in words (both screens show it: Settle up's error line and the group page's). D-247 fix
     *  wave (I1): only the person's next Settle tap clears it, BEFORE that tap lands the settle already held, so a failure
     *  of that landing is kept and shown; a write that succeeds never clears it. */
    var failure by mutableStateOf<String?>(null)
        private set

    private var nextId = 0
    private var timer: Job? = null
    private var toastSerial: Int? = null

    /** What the screens add to the ledger's settlements while a settle is held. */
    val held: List<SettlementDTO> get() = pending?.let { listOf(it.settlement) } ?: emptyList()

    /** [read] with what is held, under the lock a write takes: a screen's read of the vault's settlements and the held
     *  one is never split by a write landing between them. */
    fun <T> counting(read: (List<SettlementDTO>) -> T): T = synchronized(lock) { read(held) }

    /** Holds [settlement] (to [other], by name) for the window: the write's own check runs first
     *  ([LedgerStore.checkSettlement]: this account's place, then core's rules; CLAUDE.md 10: every write path; a refusal
     *  throws and nothing is held, nothing is landed), a settle already held lands first, then the toast offers Undo. D-247
     *  fix wave (I1): the old failure clears before that landing, so the landing's own failure stays. */
    fun hold(settlement: SettlementDTO, other: String, store: LedgerStore) {
        store.checkSettlement(settlement)
        failure = null
        commit()
        nextId += 1
        val id = nextId
        synchronized(lock) { pending = Pending(id, settlement, other, store.path) }
        revision += 1
        val seconds = this.seconds ?: window(screenReader())
        center.post(DoneMoments.lineWithAction(DoneCopy.settledWith(other), DoneCopy.UNDO, seconds), clock(), onAction = { undo(id) })
        toastSerial = center.serial
        timer?.cancel()
        timer = scope.launch {
            delay((seconds * 1000).toLong())
            commit(ifStill = id)
        }
    }

    /** Undo: the held settle numbered [id] is dropped and nothing is written ("Not recorded."). A settle already written
     *  (or a newer one) is left alone. */
    fun undo(id: Int) {
        if (pending?.id != id) return
        timer?.cancel()
        timer = null
        synchronized(lock) { pending = null }
        revision += 1
        center.post(DoneMoments.line(DoneCopy.NOT_RECORDED), clock())
    }

    /** The window ended for [ifStill]: it lands, unless it was undone or already landed. */
    fun commit(ifStill: Int) {
        if (pending?.id == ifStill) commit()
    }

    /** The held settle lands now (written once), and its Undo goes with the toast. Nothing held: nothing happens.
     *  True when a held settle was written now (the removal's Settle first works its payments out again then). */
    fun commit(): Boolean {
        val p = pending ?: return false
        timer?.cancel()
        timer = null
        toastSerial?.let { center.clear(ifStill = it) }
        toastSerial = null
        val failed = synchronized(lock) {
            pending = null
            runCatching { write(p.path, p.settlement) }.exceptionOrNull()
        }
        return if (failed == null) {
            revision += 1
            written(p.path)
            true
        } else {
            failure = "Could not record the settlement. ${failed.message ?: failed}"
            revision += 1
            false
        }
    }

    companion object {
        /** The app's one hold: Settle up's cards and the group page's next card share it. */
        val shared: SettleHold by lazy { SettleHold() }

        /** D-247 fix wave (decision 201): the window with TalkBack running, long enough to hear the toast and reach its
         *  Undo (WCAG 2.2.1's "enough time"). */
        const val SCREEN_READER_SECONDS = 12.0

        /** D-247 fix wave (decision 201): how long a settle is held, and its Undo toast shown: [DoneCenter.ACTION_SECONDS]
         *  (5), or [SCREEN_READER_SECONDS] (12) while a screen reader runs. The twin of iOS's `SettleHold.window`. */
        fun window(screenReader: Boolean): Double = if (screenReader) SCREEN_READER_SECONDS else DoneCenter.ACTION_SECONDS

        /** Section 10.3: saved locally first, then the push (fire and forget), and every screen reads again. */
        fun liveWritten(path: String) {
            CloudSync.ledgerChanged(path)
            SyncStatus.changed()
        }
    }
}

/** D-247 fix wave (decision 201): whether a screen reader runs now: TalkBack's touch exploration (the twin of iOS's
 *  `UIAccessibility.isVoiceOverRunning`). False before the app has a context (a JVM test). */
object ScreenReader {
    fun running(): Boolean = runCatching {
        val manager = VaultGate.appContextRef.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        manager.isEnabled && manager.isTouchExplorationEnabled
    }.getOrDefault(false)
}

/** D-247: a screen that shows held settles (Settle up, a group's page) lands the held one when it goes away: popped,
 *  pushed over or its tab left (the shell disposes all three). Never for a re-creation (a theme change): the person is
 *  still here. The twin of iOS's `landsHeldSettle(visible:)`. */
@Composable
fun LandsHeldSettle() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose { if (context.findActivity()?.isChangingConfigurations != true) SettleHold.shared.commit() }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * D-182: Settle up across every group (the founder's canvas): a section per
 * group, a card per person you owe or who owes you, and "all settled" for a
 * group with nothing left. Recording is §5.8's path, unchanged: a settlement
 * record in the ledger (SettleUi.settlementFor), never a flag, never money.
 * `SettleScreen` stays for the `--settle` proof. D-247: every settle (the whole
 * debt or another amount) is held for Undo ([SettleHold]); the toast says it.
 */
@Composable
fun SettleAllScreen(
    vaultPath: String,
    dark: Boolean,
    onOpenAccount: (() -> Unit)? = null,
    /** D-225 T7 (A5): the tab's place, kept by the shell across a pushed route or another tab. */
    keep: TabKeep = remember { TabKeep() },
) {
    var sections by keep.slot<List<SettleSection>>("sections") { emptyList() }
    // D-221 fix wave (S1): a card stays "Recording…" from its tap until a reload no longer shows that payment, so a
    // second tap in the reload's window records nothing; only a failure lets go. One at a time: while one records, every
    // other card's Settle reads as disabled.
    val latch = remember { SettleLatch<SettleKey>() }
    var error by keep.slot<String?>("error") { null }
    var loaded by keep.slot("loaded") { false }
    // D-234 (L7): every group, read-only copies too (Settle up lists only the ones that can be recorded in).
    var groupCount by keep.slot("groupCount") { 0 }
    // D-247: the card whose "Another amount" sheet is open.
    var otherAmount by remember { mutableStateOf<OtherAmountDraft?>(null) }
    val hold = SettleHold.shared

    // D-247: a settle held, undone, written or failed changes what the cards count.
    LaunchedEffect(vaultPath, SyncStatus.revision, hold.revision) {
        withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                hold.counting { held -> SettleAll.sections(store, held) } to store.namedGroups().size
            }
        }.fold(
            onSuccess = { (found, total) ->
                sections = found
                groupCount = total
                latch.moved(found.flatMap { s -> s.keys })
                // D-247: the look's surface only (`--groups-proof --settle-look sheet|toomuch`, debuggable builds).
                SettleLook.draft(found)?.let { otherAmount = it }
                error = null
            },
            onFailure = {
                error = if (it is NeedsYourNameException) "Add your name first."
                else "Could not load the ledger. ${it.message ?: it}"
            },
        )
        loaded = true
    }
    // D-247: leaving Settle up (another tab, a pushed page) lands a held settle.
    LandsHeldSettle()

    /** D-247 (decision 182): the card's settle, the whole debt or another amount, held for Undo ([SettleHold], the one
     *  write path; the group page's next card holds through it too). The hold's revision reloads the cards. */
    fun settle(entry: ShareOut, currency: String, section: SettleSection, key: SettleKey, amount: Long? = null) {
        if (!latch.begin(key)) return
        // A removed person keeps their name (the history's).
        val other = LedgerStore.label(entry.id, section.named, section.history)
        val failure = runCatching {
            hold.hold(SettleAll.settlement(entry, currency, section.named, amount), other, LedgerStore(vaultPath))
        }.exceptionOrNull()
        if (failure == null) {
            error = null
        } else {
            latch.failed()
            error = "Could not record the settlement. ${failure.message ?: failure}"
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
        // D-247: the toast says a settle is done (with its Undo); a write that failed says so here (the group page shows
        // the same line).
        (error ?: hold.failure)?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark))) }
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
            if (section.state != SettleState.OPEN) {
                // D-247 (decision 168): "all settled" only when core says the whole group is.
                val all = section.state == SettleState.ALL_SETTLED
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
                        Text(
                            if (all) SettleAllCopy.settled(named.group.name) else SettleAllCopy.youSettled(named.group.name),
                            style = Type.label(dark, 15).copy(color = Tok.owing(dark)),
                        )
                        Text(
                            if (all) SettleAllCopy.SETTLED_DETAIL else SettleAllCopy.YOU_SETTLED_DETAIL,
                            style = Type.bodyDim(dark).copy(fontSize = 13.sp),
                        )
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
                                settle(entry, currency, section, key)
                            }
                            // D-247 (decision 167): the quiet link under the button, the in-card link's look.
                            CardLink(SettleAllCopy.ANOTHER_AMOUNT, dark, enabled = !latch.busy) {
                                otherAmount = OtherAmountDraft(key, entry, currency, named, other, section.history)
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
    // D-247 (decision 167): the sheet, owned here (always composed while Settle up is), never by a card that can go.
    otherAmount?.let { draft ->
        OtherAmountSheet(
            draft,
            onSettle = { minor ->
                // D-247 fix wave (review M3): the cap against the live card, not the one the sheet opened on.
                val live = SettleAll.liveEntry(sections, draft.named.group.id, draft.currency, draft.entry.id, draft.youOwe)
                when (val verdict = OtherAmount.atSettle(minor, live, draft.youOwe, draft.other)) {
                    is OtherAmount.Verdict.Settles -> {
                        otherAmount = null
                        val section = sections.firstOrNull { it.named.group.id == draft.named.group.id }
                        if (section != null && live != null) {
                            settle(live, draft.currency, section, SettleKey(draft.named.group.id, draft.currency, live), verdict.minor)
                        }
                        null
                    }
                    is OtherAmount.Verdict.TooMuch -> verdict.words
                    is OtherAmount.Verdict.NotAnAmount -> verdict.words
                    OtherAmount.Verdict.Incomplete -> null
                }
            },
            onCancel = { otherAmount = null },
        )
    }
}

/** D-247 (decision 167): a quiet text link in a card: the accent in the detail size, centred, at least 44 dp to touch;
 *  dimmed and inert while [enabled] is false. The twin of iOS's plain "Another amount" button. */
@Composable
internal fun CardLink(label: String, dark: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else ButtonLook.DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.body(dark).copy(fontSize = TypeScale.detail.sp, color = Tok.accent(dark)))
    }
}

/** D-247 (decision 167): "How much was paid?": the amount field (focused, the whole debt in core's entry form), section
 *  11.6's sentence, Settle (disabled until the field holds an amount within the debt) and Cancel. A bottom sheet in the
 *  app's window style (You's picture sheet's). Writes nothing itself: Settle hands the amount back to the card, which
 *  holds it like any settle. The twin of iOS's `OtherAmountSheet`. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OtherAmountSheet(
    draft: OtherAmountDraft,
    /** Settle with this many minor units: null, taken (the sheet closes); words, refused at the tap (the card's live debt
     *  is less now, D-247 fix wave): shown as the cap's line, the sheet stays. */
    onSettle: (Long) -> String?,
    onCancel: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tok.bg(dark),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        SkiptiWindowLocals {
            val inDark = isSystemInDarkTheme()
            var text by remember(draft.key) {
                mutableStateOf(SettleLook.typed() ?: OtherAmount.prefill(draft.debt, draft.currency))
            }
            // A refusal at the tap is about what the field said then: an edit clears it.
            var refused by remember(draft.key) { mutableStateOf<String?>(null) }
            // Review M1: the field has rested since its last change (or Settle was tapped): its not-an-amount words show.
            var rested by remember(draft.key) { mutableStateOf(false) }
            LaunchedEffect(text) {
                rested = false
                kotlinx.coroutines.delay(OtherAmount.WORDS_PAUSE_MS)
                rested = true
            }
            val verdict = OtherAmount.verdict(text, draft.debt, draft.currency, draft.youOwe, draft.other) { minor ->
                ffiCheckSettlement(SettleAll.settlement(draft.entry, draft.currency, draft.named, minor).toInput())
            }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Column(Modifier.fillMaxWidth().auroraBackground(inDark).navigationBarsPadding().imePadding()) {
                androidx.compose.material3.BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                PushedBar(SettleAllCopy.HOW_MUCH_PAID, onBack = null, underStatusBar = false)
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        Modifier.fillMaxWidth().glassCard(inDark),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            SettleAllCopy.sentence(draft.youOwe, draft.other),
                            style = Type.bodyDim(inDark).copy(fontSize = TypeScale.detail.sp),
                        )
                        val big = Type.numeric(40, FontWeight.Bold, inDark).copy(textAlign = TextAlign.Center, color = Tok.text(inDark))
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                refused = null
                            },
                            singleLine = true,
                            textStyle = big,
                            cursorBrush = SolidColor(Tok.accent(inDark)),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .semantics { contentDescription = SettleAllCopy.HOW_MUCH_PAID },
                            decorationBox = { field ->
                                Box(contentAlignment = Alignment.Center) {
                                    if (text.isEmpty()) Text("0", style = big.copy(color = Tok.textFaint(inDark)))
                                    field()
                                }
                            },
                        )
                        Text(
                            draft.currency,
                            style = Type.label(inDark, 13).copy(color = ThemePills.text(PillTone.ACCENT, inDark)),
                            modifier = Modifier
                                .themePill(PillTone.ACCENT, inDark, onPage = false)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        OtherAmount.capWords(verdict, refused, rested)?.let { words ->
                            Text(words, style = Type.body(inDark).copy(fontSize = TypeScale.detail.sp, color = Tok.owed(inDark)))
                        }
                        // Section 11.6, verbatim.
                        Text(
                            SettleAllCopy.EXPLAINER,
                            style = Type.bodyDim(inDark).copy(fontSize = TypeScale.detail.sp, textAlign = TextAlign.Center),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryAction(SettleAllCopy.SETTLE, inDark, enabled = OtherAmount.tappable(verdict) && refused == null) {
                            (verdict as? OtherAmount.Verdict.Settles)?.let { refused = onSettle(it.minor) }
                            if (verdict is OtherAmount.Verdict.NotAnAmount) rested = true
                        }
                        SecondaryAction(SettleAllCopy.CANCEL, inDark, onClick = onCancel)
                    }
                }
            }
        }
    }
}
