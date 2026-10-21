package com.vardrlabs.skipti

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.ffiYourEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vardrlabs.skipti.AddExpenseUi.SplitDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.skipti_core.CategoryTree
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.SplitSpecInput
import uniffi.skipti_core.ffiCategoryTreeDefault
import uniffi.skipti_core.ffiMoneyInputDigits
import uniffi.skipti_core.ffiMoneyParseTyped
import uniffi.skipti_core.ffiSplitCompute

/** D-183: the Add sheet's words (the founder's canvas); iOS's twin is
 *  `AddSheetCopy` in AddExpenseScreen.swift. */
object AddSheetCopy {
    const val HOW_MUCH = "How much?"
    const val WHAT_FOR = "What was it for?"
    const val GROUP = "Group"
    // D-225 T7 (Design 11): the split card's labels and choice pages' titles, iOS's words.
    const val SPLIT = "Split"
    const val SPLIT_TYPE = "Split type"
    const val PAID_BY = "Paid by"

    /** D-225 T7 (A6, A8, the founder 2026-10-17): the four hints under the split, one table on both phones (iOS's
     *  AddSheetCopy carries the same four lines and both tests pin each to its kind). Kicker style; no dashes. */
    const val SHARES_HINT = "WEIGHTS, NOT AMOUNTS: 2 ADULTS + 1 CHILD AS 2:2:1"
    const val PAYER_ONLY_HINT = "One person paid, nobody owes: logged in the group"
    const val PERCENTAGE_HINT = "Percentages must add up to 100"
    const val ADJUSTMENT_HINT = "Equal split, plus or minus each adjustment"

    /** D-225 fix wave (M3): the hint a split kind shows under the split card; null for the kinds that need none
     *  (Equal, Exact, Itemised). The twin of iOS's `AddSheetCopy.hint(_:)`. */
    fun hint(kind: AddExpenseUi.SplitDraft): String? = when (kind) {
        is AddExpenseUi.SplitDraft.Percentage -> PERCENTAGE_HINT
        is AddExpenseUi.SplitDraft.Shares -> SHARES_HINT
        is AddExpenseUi.SplitDraft.Adjustment -> ADJUSTMENT_HINT
        AddExpenseUi.SplitDraft.PayerOnly -> PAYER_ONLY_HINT
        AddExpenseUi.SplitDraft.Equal, is AddExpenseUi.SplitDraft.Exact, is AddExpenseUi.SplitDraft.Itemised -> null
    }

    /** The live line under the split: what this expense does for you. */
    fun summary(effect: Long, formatted: String): String = when {
        effect > 0 -> "You’ll get back $formatted"
        effect < 0 -> "You’ll owe $formatted"
        else -> "Nothing changes for you"
    }

    /** Core's effect of the expense as it stands (ffiYourEffect), for you;
     *  null when core cannot say. */
    fun summary(expense: ExpenseInput, viewer: String, locale: LocaleTag = AppLocale.current): String? =
        runCatching {
            val e = ffiYourEffect(expense, viewer)
            summary(e.amountMinor, BalanceUi.moneyFormat(kotlin.math.abs(e.amountMinor), e.currencyCode, locale))
        }.getOrNull()
}

/** D-248 (U30.2, decision 184): every amount a person types is read by core's typed parser (`ffiMoneyParseTyped`: one
 *  ',' and no '.' is the decimal mark, so a decimal-comma keyboard can enter an amount), and a field that still holds no
 *  amount says so in words, with an example in the phone's own decimal mark. The twin of iOS's `AmountEntry`. */
object AmountEntry {
    /** A typed amount refused in words (the save-error line shows [words]). */
    class Refusal(val words: String) : IllegalArgumentException(words) {
        override fun equals(other: Any?): Boolean = other is Refusal && other.words == words
        override fun hashCode(): Int = words.hashCode()
    }

    /** The amount field's hint (U29.4: drawn in `groundFaint` at full opacity). */
    const val HINT = "0"

    /** The phone's decimal mark: ',' where its locale writes one, else '.' (the two core's typed parser reads). */
    fun mark(locale: java.util.Locale = java.util.Locale.getDefault()): String =
        if (java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator == ',') "," else "."

    /** An example amount in [currency]'s own decimals: core's entry form of 1,250 minor units ("12.50"; "1250" in JPY,
     *  "1.250" in KWD), in the phone's mark. Nothing here does money arithmetic. Null for a currency core does not know
     *  (no example is made up). */
    fun example(currency: String, locale: java.util.Locale = java.util.Locale.getDefault()): String? =
        runCatching { ffiMoneyInputDigits(1_250, currency) }.getOrNull()?.replace(".", mark(locale))

    fun likeWords(currency: String, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        example(currency, locale)?.let { "Enter an amount like $it." } ?: "Enter an amount."

    /** U30.3: an exact row left blank at Save. */
    fun forPerson(name: String): String = "Enter an amount for $name."

    /** Core's minor units for [text], or null while it is not an amount (the live lines). */
    fun minor(text: String, currency: String): Long? = runCatching { ffiMoneyParseTyped(text, currency) }.getOrNull()

    /** Save's read: core's minor units, or a [Refusal] in words. Too large keeps core's own error (SplitWords' words). */
    fun parse(text: String, currency: String, locale: java.util.Locale = java.util.Locale.getDefault()): Long =
        try {
            ffiMoneyParseTyped(text, currency)
        } catch (e: CoreException.Overflow) {
            throw e
        } catch (e: CoreException) {
            throw Refusal(likeWords(currency, locale))
        }

    fun isBlank(text: String?): Boolean = (text ?: "").isBlank()
}

/** D-248 (U29.3): when Add expense's amount takes the keyboard: a new expense, the first time its page shows (not back
 *  from a choice page, not after a save error), never on Edit. The twin of iOS's `AmountFocus`. */
object AmountFocus {
    fun focusesOnOpen(editing: Boolean, already: Boolean): Boolean = !editing && !already
}

/** D-184: why a split cannot be saved, in words, with core's amounts. A
 *  tester read "expected=3000000, got=2500000" (uniffi's message, in minor
 *  units) when four exact amounts came to ₹25,000 of ₹30,000. iOS's twin is
 *  `SplitWords` in AddExpenseScreen.swift. */
object SplitWords {
    /** The words for a split core refused; null for an error that is not
     *  about the split. */
    fun message(e: CoreException, currency: String, locale: LocaleTag = AppLocale.current): String? {
        fun money(minor: Long) = BalanceUi.moneyFormat(minor, currency, locale)
        return when (e) {
            is CoreException.ExactSumMismatch -> sums("amounts", e.expected, e.got, ::money)
            is CoreException.TotalMismatch -> sums("items", e.expected, e.got, ::money)
            is CoreException.PercentMismatch ->
                "The percentages add up to ${percent(e.gotBps)}. They need to make 100%."
            is CoreException.EmptyParticipants -> "Choose at least one person to split with."
            is CoreException.DuplicateParticipant -> "Someone is in this split twice."
            is CoreException.UnknownParticipant -> "Someone in this split is not in the group."
            is CoreException.NonPositiveWeight -> "Every share and percentage needs to be more than zero."
            is CoreException.NegativeTotal -> "The amount cannot be below zero."
            is CoreException.DeltasExceedTotal -> "The adjustments come to more than the whole expense."
            is CoreException.LineAmountNonPositive -> "Every item needs an amount above zero."
            is CoreException.EmptyLineAssignment -> "Every item needs at least one person."
            is CoreException.Overflow -> "That amount is too large."
            else -> null
        }
    }

    /** The words while the split does not add up yet, for the live line;
     *  null for anything else (those wait for Save). */
    fun live(e: Throwable, currency: String, locale: LocaleTag = AppLocale.current): String? = when (e) {
        is CoreException.ExactSumMismatch, is CoreException.TotalMismatch,
        is CoreException.PercentMismatch -> message(e as CoreException, currency, locale)
        else -> null
    }

    private fun sums(what: String, expected: Long, got: Long, money: (Long) -> String): String =
        if (got < expected) {
            "The $what add up to ${money(got)} of ${money(expected)}. " +
                "${money(expected - got)} is still to assign."
        } else {
            "The $what add up to ${money(got)}, ${money(got - expected)} more than " +
                "the ${money(expected)} spent."
        }

    /** Basis points as a percentage: 9000 -> "90%", 9050 -> "90.5%". Whole
     *  numbers only; no float (ADR-0002). */
    fun percent(bps: ULong): String {
        val whole = bps / 100u
        val rest = (bps % 100u).toInt()
        return when {
            rest == 0 -> "$whole%"
            rest % 10 == 0 -> "$whole.${rest / 10}%"
            else -> "$whole.${rest.toString().padStart(2, '0')}%"
        }
    }
}

/** D-225 (Design 11): the Group row on Add and Edit expense. A new expense with more than one group to file it under gets
 *  a chooser (a value with a chevron, opening the choice page); a new expense with one group, or any edit (an edit stays
 *  in its own group, D-143), shows the group as a plain value; no group, no row. The twin of iOS's `AddExpenseRows`. */
object AddExpenseRows {
    enum class GroupRow { CHOOSER, PLAIN, NONE }

    fun groupRow(editing: Boolean, groupCount: Int): GroupRow = when {
        editing -> GroupRow.PLAIN
        groupCount > 1 -> GroupRow.CHOOSER
        groupCount == 1 -> GroupRow.PLAIN
        else -> GroupRow.NONE
    }

    /** D-234 (L7, decision 87): the line a new expense shows when no group can take it: no groups at all, or only groups
     *  this phone can't add to (read-only copies); null when one can. The twin of iOS's `AddExpenseRows.emptyLine`. */
    const val NO_GROUPS = "Expenses belong to a group. Make one on Groups first."
    const val NONE_WRITABLE = "You can't add to your groups from this phone. Make a new group on Groups to add one."

    fun emptyLine(groupCount: Int, writableCount: Int): String? = when {
        writableCount > 0 -> null
        groupCount == 0 -> NO_GROUPS
        else -> NONE_WRITABLE
    }
}

/**
 * Computed data for the add-expense screen: the pure save path the JVM
 * tests drive against the REAL core, exactly like BalanceUi/ExpenseListUi.
 * No arithmetic happens here or in the composable: the decimal STRING goes
 * to core (ffiMoneyParseTyped, D-248's `AmountEntry`), the split goes to core (ffiSplitCompute),
 * and the record persists through the vault loop. A float never enters.
 */
object AddExpenseUi {
    /** D-235 round 3: core refuses free text past 1,000 characters (its write and ingest paths), so the note field takes
     *  no more: typing or pasting past it keeps the first 1,000. The twin of iOS's field. No copy says so. */
    const val NOTE_MAX_CHARS = LedgerStore.TEXT_MAX_CHARS

    /** Counted as core counts (code points), and cut at the last whole character (grapheme cluster) that fits, as
     *  iOS's field does: never a lone surrogate, never half an emoji. */
    fun capNote(text: String): String {
        if (LedgerStore.textChars(text) <= NOTE_MAX_CHARS) return text
        val limit = text.offsetByCodePoints(0, NOTE_MAX_CHARS)
        val characters = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        val end = characters.preceding(limit + 1).takeIf { it != java.text.BreakIterator.DONE } ?: 0
        return text.substring(0, end)
    }

    val promotedCurrencies =
        listOf("INR", "USD", "EUR", "GBP", "JPY", "AED", "SGD", "AUD", "CAD")

    /**
     * uniffi 0.32's generated `message` getter prefixes the payload with the
     * field name ("v1=amount must be…"); the user gets the payload. Blank
     * messages (Overflow, EmptyParticipants) degrade to the variant name,
     * never an empty error line.
     */
    /** D-184: the draft the live line computes, where an exact amount not
     *  typed yet counts as zero. Only the live line uses it. */
    fun liveDraft(draft: SplitDraft, participants: Set<String>): SplitDraft = when (draft) {
        is SplitDraft.Exact -> SplitDraft.Exact(
            participants.associateWith { id -> draft.amounts[id]?.takeIf { it.isNotBlank() } ?: "0" },
        )
        else -> draft
    }

    fun humanError(e: CoreException): String =
        (e.message ?: "").replace(Regex("^v1="), "")
            .ifBlank { e::class.simpleName ?: "could not save this expense" }


    /**
     * The split draft: what the FORM collects, per §5.4's table. Amounts are
     * decimal STRINGS until core parses them; percentages and weights are
     * whole-number STRINGS (iOS's rule: a non-integer percent is refused;
     * core takes basis points, and 12.5% has no integer basis-point value
     * without float maths, which never happens here).
     */
    @Serializable
    sealed class SplitDraft {
        abstract val label: String

        @Serializable
        object Equal : SplitDraft() {
            override val label = "Equal"
        }

        @Serializable
        data class Exact(val amounts: Map<String, String> = emptyMap()) : SplitDraft() {
            override val label = "Exact"
        }

        @Serializable
        data class Percentage(val percents: Map<String, String> = emptyMap()) : SplitDraft() {
            override val label = "Percentage"
        }

        @Serializable
        data class Shares(val weights: Map<String, String> = emptyMap()) : SplitDraft() {
            override val label = "Shares"
        }

        @Serializable
        data class Adjustment(val deltas: Map<String, String> = emptyMap()) : SplitDraft() {
            override val label = "Adjustment"
        }

        @Serializable
        data class Itemised(
            val lines: List<LineDraft> = emptyList(),
            val tax: String = "",
            val tip: String = "",
        ) : SplitDraft() {
            override val label = "Itemised"
        }

        @Serializable
        data class LineDraft(val amount: String = "", val who: Set<String> = emptySet())

        @Serializable
        object PayerOnly : SplitDraft() {
            override val label = "Payer only"
        }
    }

    /** D-225 T7: the kind a Split type choice names, fresh (its entries empty); the choice page's options are
     *  [allKinds]' labels in §5.4's order, iOS's `SplitKind.allCases`. */
    fun kind(label: String): SplitDraft = allKinds.first { it.label == label }

    val allKinds: List<SplitDraft> = listOf(
        SplitDraft.Equal,
        SplitDraft.Exact(),
        SplitDraft.Percentage(),
        SplitDraft.Shares(),
        SplitDraft.Adjustment(),
        SplitDraft.Itemised(),
        SplitDraft.PayerOnly,
    )

    /**
     * The spec builder: iOS's buildSpec, one-to-one. Every refusal here is
     * either CORE's (surfaced as CoreException) or the shell's own rule that
     * iOS also owns (whole-number percents). No arithmetic happens in this
     * file: minor units come from ffiMoneyParseTyped (`AmountEntry`), bps are integer
     * percents × 100, and the actual SPLIT is ffiSplitCompute's job.
     * D-248 (U30.3): tax, tip, an adjustment and an item's amount left blank are 0 (an item at 0 gets core's "Every item
     * needs an amount above zero."); an exact row left blank is named ("Enter an amount for Rahul.", [name] gives the
     * row's name); anything typed that is not an amount gets `AmountEntry`'s words, never core's raw error. The live
     * line's blanks are [liveDraft]'s.
     */
    fun buildSpec(
        draft: SplitDraft,
        roster: List<String>,
        currency: String,
        payer: String,
        name: (String) -> String = { it },
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): SplitSpecInput {
        fun optional(text: String?): Long = if (AmountEntry.isBlank(text)) 0 else AmountEntry.parse(text ?: "", currency, locale)
        fun required(text: String?, id: String): Long {
            if (AmountEntry.isBlank(text)) throw AmountEntry.Refusal(AmountEntry.forPerson(name(id)))
            return AmountEntry.parse(text ?: "", currency, locale)
        }

        return when (draft) {
            is SplitDraft.Equal -> SplitSpecInput.Equal

            is SplitDraft.Exact -> SplitSpecInput.Exact(
                entries = roster.map { id ->
                    uniffi.skipti_core.IdAmount(id = id, amountMinor = required(draft.amounts[id], id))
                },
            )

            is SplitDraft.Percentage -> SplitSpecInput.Percentage(
                entries = roster.map { id ->
                    val text = draft.percents[id] ?: ""
                    val percent = text.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw IllegalArgumentException(
                            "percentage must be a whole number of percent",
                        )
                    uniffi.skipti_core.IdBps(id = id, bps = (percent * 100L).toULong())
                },
            )

            is SplitDraft.Shares -> SplitSpecInput.Shares(
                entries = roster.map { id ->
                    uniffi.skipti_core.IdWeight(
                        id = id,
                        weight = (draft.weights[id] ?: "0").toULongOrNull() ?: 0uL,
                    )
                },
            )

            is SplitDraft.Adjustment -> SplitSpecInput.Adjustment(
                entries = roster.map { id ->
                    uniffi.skipti_core.IdAmount(id = id, amountMinor = optional(draft.deltas[id]))
                },
            )

            is SplitDraft.Itemised -> SplitSpecInput.Itemised(
                lines = draft.lines.map {
                    uniffi.skipti_core.LineInput(
                        amountMinor = optional(it.amount),
                        participants = it.who.sorted(),
                    )
                },
                taxMinor = optional(draft.tax),
                tipMinor = optional(draft.tip),
            )

            is SplitDraft.PayerOnly -> SplitSpecInput.PayerOnly(payer = payer)
        }
    }

    /**
     * The whole save path, headless-testable: parse → refuse-zero → compute
     * → build the record. THROWS on every invalid input; the caller decides
     * how to render it. Nothing is written here: the caller writes only
     * after this returns.
     */
    fun buildExpense(
        amountText: String,
        currency: String,
        note: String,
        category: String?,
        participants: Set<String>,
        draft: SplitDraft,
        payer: String,
        groupId: String,
        nowMs: Long = System.currentTimeMillis(),
        /** D-141: §5.7's "added by", the participant id of whoever adds it. */
        addedBy: String? = null,
        /** D-248 (U30.3): a member's name, for an exact row left blank ("Enter an amount for Rahul."). */
        name: (String) -> String = { it },
    ): ExpenseDTO {
        val total = AmountEntry.parse(amountText, currency)
        require(total > 0) { "the amount must be positive" }
        require(participants.isNotEmpty()) { "at least one member shares this expense" }
        val roster = participants.sorted()
        // Split types are core's full enum (D-42); shares are COMPUTED by
        // core: the shell never divides.
        val computed = ffiSplitCompute(total, currency, roster, buildSpec(draft, roster, currency, payer, name))
        // D-136: the payer and the group are the person's choice on the screen.
        // This used to call loadGroupsOrSeed(), so the first saved expense on a
        // fresh install wrote the fixture "Goa Trip", paid by the fixture Ada.
        return ExpenseDTO(
            payer = payer,
            paidMinor = total,
            currencyCode = currency,
            group = groupId,
            conversion = null,
            shares = computed.map { ExpenseDTO.ShareDTO(id = it.id, amountMinor = it.minorUnits) },
            note = note.ifEmpty { null },
            timestampMs = nowMs,
            category = category,
            addedBy = addedBy,
        )
    }
}

/** The split draft survives rotation and process death through the JSON
 *  saver: kotlinx handles the sealed hierarchy (D-112). */
private val splitDraftSaver = Saver<AddExpenseUi.SplitDraft, String>(
    save = { Json.encodeToString(AddExpenseUi.SplitDraft.serializer(), it) },
    restore = { Json.decodeFromString(AddExpenseUi.SplitDraft.serializer(), it) },
)

/**
 * §5.3/§5.4's add-expense screen: port of ios/Skipti/App/AddExpenseScreen.swift,
 * ALL SEVEN split types (§5.4's table; the equal-split-only interim from
 * D-110 is gone). Amounts enter as decimal STRINGS and become integer minor
 * units through ffiMoneyParseTyped (D-248), never through a float (ADR-0002), the
 * same rule that keeps ffiMoneyFormat on the render side.
 */
/** D-235 (H1): a save button's latch. [begin] is called in the tap itself, before any work is launched: true, and the
 *  latch is held, unless a save is already running. A failed save lets go ([release]); a successful one keeps it (the
 *  screen closes). Compose state, so the button reads [busy] and redraws; memory only. The twin of what iOS gets for free
 *  (its save runs on the main actor before the next tap is handled). */
class SaveLatch {
    var busy by mutableStateOf(false)
        private set

    fun begin(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    /** The save failed (or finished on a screen that stays): the button may be used again. */
    fun release() {
        busy = false
    }
}

@Composable
fun AddExpenseScreen(
    vaultPath: String,
    onSaved: () -> Unit = {},
    /** D-143: the id of a stored expense to EDIT instead of adding one. The
     *  form opens filled in, in the expense's own group, and saving writes one
     *  revision holding only what changed (`ExpenseEditingUi.revision`). */
    editingId: String? = null,
    onBack: (() -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

    var amountText by rememberSaveable { mutableStateOf("") }
    // D-165: a fresh form starts in the default currency; a group's own
    // settlement currency replaces it once the group loads.
    var currency by rememberSaveable { mutableStateOf(DefaultCurrency.current) }
    var note by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    /** D-181: the first of the two steps (null: follow the chosen leaf's). */
    var categoryParent by rememberSaveable { mutableStateOf<String?>(null) }
    // D-136: the selected group's people and who paid. No fixture people.
    var participants by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var payer by rememberSaveable { mutableStateOf("") }
    var named by remember { mutableStateOf<NamedGroupWire?>(null) }
    var groupLoaded by remember { mutableStateOf(false) }
    /** D-183: every group, for the Group picker inside the sheet. */
    var groups by remember { mutableStateOf<List<NamedGroupWire>>(emptyList()) }
    /** D-234 (L7): every group, read-only copies too: whether "none can take it" or "there are none". */
    var groupCount by remember { mutableStateOf(0) }
    // D-225 T7 (Design 11): the pill opens the whole currency list page; each choice opens its choice page (by name:
    // "category", "subcategory", "group", "split", "payer"; null while none is open).
    var listingCurrencies by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    // D-235 (H1): held from the tap until the save fails (a success closes the sheet), so a double tap saves once.
    val saving = remember { SaveLatch() }
    // D-143: the expense being edited, filled in once (not again after a
    // rotation, which would throw away what the person typed).
    var editing by remember { mutableStateOf<ProjectedExpenseOut?>(null) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    // D-248 (U29.3): the amount takes the keyboard when a NEW expense opens (never on Edit), once.
    val amountFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // A new expense starts at its amount, once (not again after a rotation or back from a choice page, not on Edit).
        if (AmountFocus.focusesOnOpen(editing = editingId != null, already = focusedOnce)) {
            focusedOnce = true
            runCatching { amountFocus.requestFocus() }
            keyboard?.show()
        }
    }

    // The split draft survives rotation and process death through the JSON
    // saver (kotlinx handles the sealed hierarchy). Switching kinds starts
    // that kind fresh; iOS keeps per-kind dictionaries instead: a UI-state
    // nuance, noted in D-112.
    var draft by rememberSaveable(stateSaver = splitDraftSaver) {
        mutableStateOf<SplitDraft>(SplitDraft.Equal)
    }

    // §8.1's leaves, re-fetched per composition: cheap, and the tree is small.
    val tree = rememberCategoryTree()
    // D-136: the selected group. You pay and everyone takes part by default.
    LaunchedEffect(vaultPath, editingId) {
        // D-143: an edit opens in the expense's OWN group, filled in once.
        if (editingId != null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val store = LedgerStore(vaultPath)
                    val found = store.projectedExpenses().expenses
                        .firstOrNull { it.id == editingId && !it.deleted }
                    val own = found?.let { e ->
                        store.namedGroups().firstOrNull { it.group.id == e.expense.group }
                    }
                    found to own
                }
            }
            loaded.fold(
                onSuccess = { (found, own) ->
                    editing = found
                    named = own
                    if (found == null) {
                        error = "This expense is not in your ledger."
                    } else if (!prefilled) {
                        runCatching { ExpenseEditingUi.prefill(found) }.fold(
                            onSuccess = { form ->
                                amountText = form.amountText
                                currency = form.currency
                                note = form.note
                                selectedCategory = form.category
                                payer = form.payer
                                participants = form.participants
                                draft = form.draft
                                prefilled = true
                            },
                            onFailure = { error = it.message ?: it.toString() },
                        )
                    }
                },
                onFailure = { error = it.message ?: it.toString() },
            )
            groupLoaded = true
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                // D-183: the sheet can file it under any group. D-210: not a
                // read-only copy, which takes nothing new.
                val copies = store.readOnlyChecked().keys
                val all = store.namedGroups()
                val open = all.filter { it.group.id !in copies }
                val shown = store.currentGroup()
                Triple(shown?.let { s -> open.firstOrNull { it.group.id == s.group.id } } ?: open.firstOrNull(), open, all.size)
            }
        }
        result.fold(
            onSuccess = { (current, all, total) ->
                groups = all
                groupCount = total
                named = named ?: current
                if (current != null) {
                    if (payer !in current.group.members) payer = current.selfId
                    if (participants.isEmpty()) participants = current.group.members.toSet()
                    // A fresh form starts in the group's settlement currency
                    // (twin of D-135; iOS started in USD for an INR group).
                    if (amountText.isEmpty()) currency = current.group.settlementCurrency
                }
            },
            onFailure = {
                error = if (it is NeedsYourNameException) {
                    "Add your name first."
                } else {
                    it.message?.takeIf { m -> m.isNotBlank() } ?: it.toString()
                }
            },
        )
        groupLoaded = true
    }
    val members = named?.let { group ->
        group.members.map { it.id to LedgerStore.label(it.id, group) }
    } ?: emptyList()
    /** D-248 (U30.3): a member's name, for an exact row left blank at Save. */
    val nameOf: (String) -> String = { id -> members.firstOrNull { it.first == id }?.second ?: "" }

    /** D-183: another group: its people, you paying, everyone in, and its
     *  currency unless one was picked for this expense. */
    fun choose(id: String?) {
        val next = groups.firstOrNull { it.group.id == id } ?: return
        if (next.group.id == named?.group?.id) return
        val previousCurrency = named?.group?.settlementCurrency
        named = next
        payer = next.selfId
        participants = next.group.members.toSet()
        draft = AddExpenseUi.allKinds.first { it::class == draft::class }
        if (amountText.isEmpty() || currency == previousCurrency) currency = next.group.settlementCurrency
    }

    // D-183: the summary line, from core: the split as it stands, then
    // ffiYourEffect. Null while the form cannot be computed yet.
    // D-184: or, while the split does not add up, how far off it is.
    val built = named?.let { g ->
        runCatching {
            AddExpenseUi.buildExpense(amountText, currency, note, selectedCategory, participants, draft, payer, g.group.id, name = nameOf)
        }
    }
    val summaryLine = named?.let { g ->
        built?.getOrNull()?.let { AddSheetCopy.summary(it.toInput(), g.selfId) }
    }
    // An amount not typed yet counts as nothing here, so the line shows while
    // the person is still filling the amounts in; Save still wants each one.
    val splitProblem = named?.let { g ->
        runCatching {
            AddExpenseUi.buildExpense(
                amountText, currency, note, selectedCategory, participants,
                AddExpenseUi.liveDraft(draft, participants), payer, g.group.id,
            )
        }.exceptionOrNull()?.let { SplitWords.live(it, currency) }
    }

    // D-217 Task 8: iOS's sheet (HomeShell.swift, ExpenseListView.swift, ExpenseDetailView.swift): a Cancel pill in the
    // bar on both Add and Edit (Cancel, not Back), the LARGE title "Add expense" / "Edit expense" under it, and Save as the
    // pinned primary action (`formActionBar`), not a button in the scroll.
    SheetPage(
        title = if (editingId != null) "Edit expense" else "Add expense",
        dark = dark,
        onCancel = onBack,
        spacing = 10.dp,
        largeTitle = true,
        actions = {
            PrimaryAction(
                // D-224 (Design 8): no label phase after a save; the toast says it on the screen Add closes to.
                label = when {
                    editingId != null -> "Save changes"
                    else -> "Add expense"
                },
                dark = dark,
                enabled = amountText.isNotEmpty() && !saved && !saving.busy && named != null,
                onClick = {
                    // D-235 (H1): taken BEFORE the save is launched; a second tap in the same moment finds it held.
                    if (saved || !saving.begin()) return@PrimaryAction
                    error = null
                    scope.launch {
                        var paid: Pair<Long, String>? = null
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val group = named
                                    ?: throw IllegalStateException("Create a group first.")
                                val expense = AddExpenseUi.buildExpense(
                                    amountText = amountText,
                                    currency = currency,
                                    note = note,
                                    category = selectedCategory,
                                    participants = participants,
                                    draft = draft,
                                    payer = payer,
                                    groupId = group.group.id,
                                    addedBy = group.selfId,
                                    name = nameOf,
                                )
                                val target = editing
                                if (target != null) {
                                    // D-143: ONE revision with only what changed, or
                                    // nothing when nothing did; the date and the
                                    // group stay the creation's.
                                    ExpenseEditingUi.revision(
                                        editing = target,
                                        money = expense.toInput(),
                                        note = note,
                                        category = selectedCategory,
                                        editor = group.selfId,
                                    )?.let { LedgerStore(vaultPath).reviseExpense(it) }
                                } else {
                                    LedgerStore(vaultPath).appendExpense(expense)
                                }
                                // §10.3: the ledger saved locally FIRST; the
                                // sync attempt is fire-and-forget and never
                                // blocks or breaks this flow.
                                CloudSync.ledgerChanged(vaultPath)
                                paid = expense.paidMinor to expense.currencyCode
                                null
                            } catch (e: AmountEntry.Refusal) {
                                e.words
                            } catch (e: CoreException) {
                                SplitWords.message(e, currency) ?: AddExpenseUi.humanError(e)
                            } catch (e: Exception) {
                                e.message?.takeIf { it.isNotBlank() } ?: e.toString()
                            }
                        }
                        if (result == null) {
                            // §10.3 is the sync rule this screen already honours
                            // structurally: the expense is SAVED locally, and
                            // nothing here waits on a relay. The shell switches
                            // to the Balance tab so the recorded debt is what
                            // the user sees next (iOS dismisses after save).
                            // D-224 (Design 7, 8; GUARANTEES #2): the toast, posted and forgotten, then Add closes at once.
                            saved = true
                            paid?.let { (minor, code) -> DoneCenter.post(DoneMoments.expense(editingId != null, note, minor, code)) }
                            onSaved()
                        } else {
                            error = result
                            saving.release()
                        }
                    }
                },
            )
        },
    ) {
        val emptyLine = AddExpenseRows.emptyLine(groupCount, groups.size)
        if (groupLoaded && named == null && error == null && emptyLine != null) {
            Text(emptyLine, style = Type.bodyDim(dark))
        }

        // D-183 (the founder's canvas): the amount first, big and centred,
        // its currency on a pill.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(AddSheetCopy.HOW_MUCH, style = Type.bodyDim(dark).copy(fontSize = 14.sp))
            val big = Type.numeric(48, dark = dark).copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            BasicTextField(
                value = amountText,
                onValueChange = { amountText = it },
                singleLine = true,
                textStyle = big,
                cursorBrush = SolidColor(Tok.accent(dark)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus)
                    .semantics { contentDescription = "Amount" },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.Center) {
                        // A hint, not an amount. D-248 (U29.4): `groundFaint` at full opacity on the page ground (the old
                        // 45% textFaint came to about 2:1 in Norse), as iOS's prompt.
                        if (amountText.isEmpty()) Text(AmountEntry.HINT, style = big.copy(color = Tok.groundFaint(dark)))
                        field()
                    }
                },
            )
            // D-220 (L19): the currency pill, on the page ground. D-225 T7 (Design 11): it opens the currency list page
            // directly, as iOS's does (the list's "Common" section holds the promoted codes; the chip row is gone).
            Text(
                currency,
                style = Type.label(dark, 13).copy(color = ThemePills.text(PillTone.ACCENT, dark)),
                modifier = Modifier
                    .themePill(PillTone.ACCENT, dark, onPage = true)
                    .clickable(role = Role.Button) { listingCurrencies = true }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Currency, $currency" },
            )
        }

        // D-225 T7 (Design 11): what it was for, its category in two steps (D-181: the category, then only its own
        // sub-categories; only a sub-category is assigned, SPEC 8.1), and the group, in one list card. Each choice is a
        // value row opening its choice page.
        val parentNow = categoryParent ?: CategoryLevels.parent(selectedCategory, tree)
        val shown = named
        val groupRow = if (shown == null) AddExpenseRows.GroupRow.NONE else AddExpenseRows.groupRow(editingId != null, groups.size)
        ListCard(dark) {
            row { FieldRow(note, { note = AddExpenseUi.capNote(it) }, AddSheetCopy.WHAT_FOR, dark) }
            row {
                ListRow(
                    CategoryCopy.CATEGORY, dark,
                    trailing = RowTrailing.Value(parentNow?.let { CategoryLevels.name(it, tree) } ?: CategoryCopy.NONE, chevron = true),
                ) { choosing = "category" }
            }
            if (parentNow != null) {
                row {
                    ListRow(
                        CategoryCopy.SUBCATEGORY, dark,
                        trailing = RowTrailing.Value(selectedCategory?.let { CategoryLevels.name(it, tree) } ?: CategoryCopy.CHOOSE, chevron = true),
                    ) { choosing = "subcategory" }
                }
            }
            // D-183: a new expense goes in any group (the shown one first); an edit stays in its own (D-143).
            if (shown != null && groupRow != AddExpenseRows.GroupRow.NONE) {
                row {
                    val chooser = groupRow == AddExpenseRows.GroupRow.CHOOSER
                    ListRow(
                        AddSheetCopy.GROUP, dark,
                        trailing = RowTrailing.Value(shown.group.name, chevron = chooser),
                        onClick = if (chooser) ({ choosing = "group" }) else null,
                    )
                }
            }
        }
        if (parentNow != null && selectedCategory == null) {
            CardCaption(CategoryCopy.hint(CategoryLevels.name(parentNow, tree)), dark)
        }

        SectionLabel(AddSheetCopy.SPLIT, dark)

        // The split card: the kind and who paid (choice pages), one switch row per person (everyone in by default),
        // then the kind's own fields as field rows, only for people in the split, in the group's order (iOS's).
        // The shares themselves come from core at save time.
        ListCard(dark) {
            row {
                ListRow(AddSheetCopy.SPLIT_TYPE, dark, trailing = RowTrailing.Value(draft.label, chevron = true)) { choosing = "split" }
            }
            if (members.isNotEmpty()) {
                row {
                    ListRow(
                        AddSheetCopy.PAID_BY, dark,
                        trailing = RowTrailing.Value(members.firstOrNull { it.first == payer }?.second ?: "", chevron = true),
                    ) { choosing = "payer" }
                }
            }
            members.forEach { (id, name) ->
                row {
                    ListRow(
                        name, dark,
                        trailing = RowTrailing.Toggle(id in participants) { on ->
                            participants = if (on) participants + id else participants - id
                        },
                    )
                }
            }
            splitFieldRows(draft, members.map { it.first }.filter { it in participants }, members, dark) { draft = it }
        }
        SplitKindExtras(draft, members, dark) { draft = it }

        // D-183: what this expense does for you, as it stands (core's).
        summaryLine?.let {
            Text(
                it,
                style = Type.body(dark).copy(color = Tok.textDim(dark), fontSize = TypeScale.prose.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        splitProblem?.let {
            Text(
                it,
                style = Type.body(dark).copy(color = Tok.owed(dark), fontSize = TypeScale.prose.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // The live line already says it; Save's refusal does not repeat it.
        error?.takeIf { it != splitProblem }?.let { msg ->
            Text(
                msg,
                style = Type.bodyDim(dark).copy(color = Tok.owed(dark)),
                modifier = Modifier.semantics {
                    contentDescription = "Could not save. $msg"
                },
            )
        }

        Text(
            Type.kicker("Recorded in your ledger. It does not move money."),
            style = Type.microLabel(dark),
        )
    }

    // D-225 T7 (Designs 10, 11): the pushed pages the rows open (each a window of its own, as the currency list).
    if (listingCurrencies) {
        CurrencyListDialog(
            selected = currency,
            dark = dark,
            onPick = {
                currency = it
                listingCurrencies = false
            },
            onClose = { listingCurrencies = false },
        )
    }
    val close = { choosing = null }
    when (choosing) {
        "category" -> ChoicePage(
            CategoryCopy.CATEGORY,
            listOf(ChoiceOption<String?>(null, CategoryCopy.NONE)) + CategoryLevels.parents(tree).map { (id, name) -> ChoiceOption<String?>(id, name) },
            chosen = categoryParent ?: CategoryLevels.parent(selectedCategory, tree),
            dark = dark,
            onPick = {
                categoryParent = it
                selectedCategory = CategoryLevels.keep(selectedCategory, it, tree)
            },
            onClose = close,
        )
        "subcategory" -> ChoicePage(
            CategoryCopy.SUBCATEGORY,
            CategoryLevels.leaves(categoryParent ?: CategoryLevels.parent(selectedCategory, tree), tree).map { (id, name) -> ChoiceOption<String?>(id, name) },
            chosen = selectedCategory,
            dark = dark,
            onPick = { selectedCategory = it },
            onClose = close,
        )
        "group" -> ChoicePage(
            AddSheetCopy.GROUP,
            groups.map { ChoiceOption(it.group.id, it.group.name) },
            chosen = named?.group?.id,
            dark = dark,
            onPick = { choose(it) },
            onClose = close,
        )
        "split" -> ChoicePage(
            AddSheetCopy.SPLIT_TYPE,
            AddExpenseUi.allKinds.map { ChoiceOption(it.label, it.label) },
            chosen = draft.label,
            dark = dark,
            // Picking the kind already chosen keeps its entries; another kind starts fresh.
            onPick = { if (it != draft.label) draft = AddExpenseUi.kind(it) },
            onClose = close,
        )
        "payer" -> ChoicePage(
            AddSheetCopy.PAID_BY,
            members.map { (id, name) -> ChoiceOption(id, name) },
            chosen = payer,
            dark = dark,
            onPick = { payer = it },
            onClose = close,
        )
    }
}

@Composable
private fun rememberCategoryTree(): CategoryTree =
    androidx.compose.runtime.remember { ffiCategoryTreeDefault() }

/** §5.4's per-kind entry fields as rows of the split card, mirroring iOS's kindFields: one field row per person in the
 *  split, in the group's order as the switch rows above (iOS's `ForEach(members)`; the draft is keyed by id, so the
 *  order is display only), with today's placeholders and keyboards. D-225 T7 (Design 11). */
private fun ListCardScope.splitFieldRows(
    draft: SplitDraft,
    roster: List<String>,
    members: List<Pair<String, String>>,
    dark: Boolean,
    onDraft: (SplitDraft) -> Unit,
) {
    val names = members.toMap()
    fun name(id: String) = names[id] ?: id
    val numeric = Type.numeric(TypeScale.shareInput, dark = dark)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    when (draft) {
        is SplitDraft.Exact -> roster.forEach { id ->
            row {
                FieldRow(draft.amounts[id] ?: "", { onDraft(draft.copy(amounts = draft.amounts + (id to it))) }, "Amount for ${name(id)}",
                    dark, style = numeric, keyboard = decimal)
            }
        }
        is SplitDraft.Percentage -> roster.forEach { id ->
            row {
                FieldRow(draft.percents[id] ?: "", { onDraft(draft.copy(percents = draft.percents + (id to it))) }, "Percent for ${name(id)}",
                    dark, style = numeric, keyboard = number)
            }
        }
        is SplitDraft.Shares -> roster.forEach { id ->
            row {
                FieldRow(draft.weights[id] ?: "", { onDraft(draft.copy(weights = draft.weights + (id to it))) }, "Shares for ${name(id)}",
                    dark, style = numeric, keyboard = number)
            }
        }
        is SplitDraft.Adjustment -> roster.forEach { id ->
            row {
                FieldRow(draft.deltas[id] ?: "", { onDraft(draft.copy(deltas = draft.deltas + (id to it))) }, "Adjustment for ${name(id)}",
                    dark, style = numeric, keyboard = decimal)
            }
        }
        is SplitDraft.Itemised, SplitDraft.Equal, SplitDraft.PayerOnly -> Unit
    }
}

/** What follows the split card for a kind: its hint (A6, A8: [AddSheetCopy.hint], one table with iOS's), and
 *  Itemised's lines, each a list card of its amount and a switch row per person, then "Add line" and the Tax and Tip
 *  card. D-225 T7 (Design 11). */
@Composable
private fun SplitKindExtras(
    draft: SplitDraft,
    members: List<Pair<String, String>>,
    dark: Boolean,
    onDraft: (SplitDraft) -> Unit,
) {
    val numeric = Type.numeric(TypeScale.shareInput, dark = dark)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AddSheetCopy.hint(draft)?.let { Text(Type.kicker(it), style = Type.microLabel(dark)) }
    when (draft) {
        is SplitDraft.Itemised -> {
            fun line(index: Int, change: (SplitDraft.LineDraft) -> SplitDraft.LineDraft) =
                onDraft(draft.copy(lines = draft.lines.mapIndexed { i, l -> if (i == index) change(l) else l }))
            draft.lines.forEachIndexed { index, line ->
                ListCard(dark) {
                    row {
                        FieldRow(line.amount, { text -> line(index) { it.copy(amount = text) } }, "Line amount",
                            dark, style = numeric, keyboard = decimal)
                    }
                    members.forEach { (id, name) ->
                        row {
                            ListRow(
                                "Line for $name", dark,
                                trailing = RowTrailing.Toggle(id in line.who) { on ->
                                    line(index) { it.copy(who = if (on) it.who + id else it.who - id) }
                                },
                            )
                        }
                    }
                }
            }
            SecondaryAction("Add line", dark) { onDraft(draft.copy(lines = draft.lines + SplitDraft.LineDraft())) }
            ListCard(dark) {
                row { FieldRow(draft.tax, { onDraft(draft.copy(tax = it)) }, "Tax", dark, style = numeric, keyboard = decimal) }
                row { FieldRow(draft.tip, { onDraft(draft.copy(tip = it)) }, "Tip", dark, style = numeric, keyboard = decimal) }
            }
        }
        is SplitDraft.Exact, SplitDraft.Equal, is SplitDraft.Percentage, is SplitDraft.Shares, is SplitDraft.Adjustment,
        SplitDraft.PayerOnly -> Unit
    }
}
