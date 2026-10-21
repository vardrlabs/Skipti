package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.LocaleTag
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiMoneyFormat
import uniffi.skipti_core.ffiMoneyFormatDecomposition
import uniffi.skipti_core.ffiMoneySpoken

/**
 * §11.4's signature line, computed data: a pure decision layer the JVM unit
 * tests drive against the REAL core through the same JNA bindings the app
 * uses (no mocks, no android.jar). Every money string comes from core FFI:
 * ffiMoneyFormat / ffiMoneyFormatDecomposition / ffiMoneySpoken, never a
 * Kotlin NumberFormat, never string building (D-46's rule, AppRoot's header).
 */
object BalanceUi {
    /** §11.6's empty-state copy, verbatim. */
    const val EMPTY_LEDGER_COPY =
        "Nothing here yet. Add your first expense."

    enum class State { LOADING, EMPTY, CONTENT }

    fun stateFor(loadedLines: List<BalanceLineOut>?): State = when {
        loadedLines == null -> State.LOADING
        loadedLines.isEmpty() -> State.EMPTY
        else -> State.CONTENT
    }

    /**
     * ONE LINE PER CURRENCY, biggest absolute net first (D-50: the UI
     * discards nothing); ties by currency code, mirroring the Swift sort.
     */
    fun sortedLines(lines: List<BalanceLineOut>): List<BalanceLineOut> =
        lines.sortedWith(
            compareByDescending<BalanceLineOut> { kotlin.math.abs(it.viewerNetMinor) }
                .thenByDescending { it.currencyCode }
        )

    /** §5.2: the frozen rate's age in whole days; null when nothing converted. */
    fun stalenessDays(nowMs: Long, expenses: List<ExpenseInput>): Int? =
        expenses
            .mapNotNull { it.conversion?.rateTimestampMs?.toLong() }
            .minOrNull()
            ?.let { oldest ->
                ((nowMs - oldest) / (24L * 60L * 60L * 1000L)).coerceAtLeast(0).toInt()
            }

    /** Positive = money comes to you (D-47's viewer frame), signed out of core. */
    fun captionFor(netMinor: Long): String = when {
        netMinor > 0 -> "you are owed"
        netMinor < 0 -> "you owe"
        else -> "settled"
    }

    /** §11.2's money colours; the sign is always printed alongside (never colour-only). */
    fun tokenFor(minor: Long): Tok = when {
        minor > 0 -> Tok.owing
        minor < 0 -> Tok.owed
        else -> Tok.neutral
    }

    /** §5.2: the settlement currency is named in the micro-label, with the group. */
    fun headerFor(line: BalanceLineOut, groupName: String?, memberCount: Int): String =
        "${groupName ?: "Goa trip"}${Separator.meta()}${if (memberCount == 1) "1 member" else "$memberCount members"}${Separator.meta()}" +
            "settles in ${line.currencyCode}"

    fun spokenHeaderFor(line: BalanceLineOut, groupName: String?, memberCount: Int): String =
        "${groupName ?: "Goa trip"}, $memberCount members, settled in ${line.currencyCode}"

    /**
     * ALL money strings route through core. A failure is a programming bug
     * (bad currency), never a user condition: the screen shows
     * [Placeholder.NONE], and the spoken amount is empty, as on iOS (D-228
     * decision 43), so TalkBack never reads the hyphen as a minus.
     */
    fun moneyFormat(minor: Long, currencyCode: String, locale: LocaleTag): String =
        try { ffiMoneyFormat(minor, currencyCode, locale) } catch (_: Exception) { Placeholder.NONE }

    fun moneyDecomposition(minor: Long, currencyCode: String, locale: LocaleTag): String =
        try { ffiMoneyFormatDecomposition(minor, currencyCode, locale) } catch (_: Exception) { Placeholder.NONE }

    fun moneySpoken(minor: Long, currencyCode: String, locale: LocaleTag): String =
        try { ffiMoneySpoken(minor, currencyCode, locale) } catch (_: Exception) { "" }

    /** The rendered decomposition for one line, in core's order. */
    fun renderedDecompositions(line: BalanceLineOut, locale: LocaleTag): List<String> =
        line.entries.map { moneyDecomposition(it.minorUnits, line.currencyCode, locale) }

    /**
     * The screen's one computation path: REAL core, viewer-framed (D-47),
     * then magnitude-sorted for render.
     */
    fun computeLines(expenses: List<ExpenseInput>, group: String?): List<BalanceLineOut> =
        sortedLines(ffiBalanceLine(expenses, emptyList(), emptyList(), emptyList(), BalanceFixtures.ada, group))
}

/** D-136: what the home shows before a ledger line: no group yet, or a group
 *  from before names existed that is waiting for your name. */
private enum class BalanceGate { READY, NO_GROUPS, NEEDS_NAME }

/** Which ledger the screen computes from. All of them run the REAL core path. */
enum class BalanceSource {
    /** The mixed-direction fixture ledger (iOS `.fixture`). Headless
     *  verification only, never the home (fixtures are not the user's). */
    FIXTURE,

    /** A genuinely empty ledger: proves §11.6's empty state, not a blank screen. */
    EMPTY,

    /**
     * The user's own ledger, READ-ONLY: no seed, no fixtures. Empty until
     * they add something, rendering §11.6's copy. This is the home.
     */
    VAULT,

    /**
     * The vault loop WITH the fixture seed (iOS `.vault`'s semantics):
     * seed → close → REOPEN → read back → compute, so the line renders
     * from persisted bytes. Headless verification only.
     */
    VAULT_FIXTURES,
}

/**
 * §11.4's balance screen: port of ios/Skipti/App/BalanceScreen.swift's
 * fixture source. Two things render here that iOS verifies the same way:
 * the staleness indicator (§5.2/§11.2 WATCH) and one §11.4 line per
 * currency with its inline per-person decomposition. The entries are in
 * the VIEWER's frame of reference (positive means money comes to you)
 * because that framing lives in core (D-47); the view never negates.
 */
@Composable
fun BalanceScreen(
    source: BalanceSource,
    version: String,
    vaultPath: String? = null,
    /** D-126: the account surface's entry point. Absent in the headless
     *  verification mounts, which have no navigation to route into. */
    /** D-136: the group routes. Absent in the headless verification mounts. */
    onOpenGroups: (() -> Unit)? = null,
    onCreateGroup: (() -> Unit)? = null,
    onAddYourName: (() -> Unit)? = null,
    /** D-166: the empty state's import, which its sentence promised. */
    onImport: (() -> Unit)? = null,
    /** D-172: Join a group, for everyone (the founder, 2026-09-26). */
    onJoin: (() -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    var lines by remember { mutableStateOf<List<BalanceLineOut>?>(null) }
    // D-136: the selected group (VAULT only) and the two states before it.
    var named by remember { mutableStateOf<NamedGroupWire?>(null) }
    var gate by remember { mutableStateOf(BalanceGate.READY) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var staleness by remember { mutableStateOf<Int?>(null) }
    var groupInfo by remember {
        mutableStateOf(BalanceFixtures.GROUP_NAME to BalanceFixtures.MEMBER_COUNT)
    }

    // §11.5: no skeleton shimmer. The data is local, so the loading state
    // is one tick of the same "…" the iOS screen shows while core computes.
    // All of it (vault I/O AND the FFI compute) runs off the main thread.
    // D-178: read again when sync brings records.
    LaunchedEffect(source, SyncStatus.revision) {
        try {
            val result = withContext(Dispatchers.IO) {
                when (source) {
                    BalanceSource.FIXTURE -> {
                        val expenses = BalanceFixtures.dto(BalanceFixtures.ada)
                        Triple(
                            BalanceFixtures.GROUP_NAME to BalanceFixtures.MEMBER_COUNT,
                            BalanceUi.stalenessDays(System.currentTimeMillis(), expenses),
                            BalanceUi.computeLines(expenses, group = BalanceFixtures.ada),
                        )
                    }

                    BalanceSource.EMPTY ->
                        Triple(
                            BalanceFixtures.GROUP_NAME to BalanceFixtures.MEMBER_COUNT,
                            null,
                            BalanceUi.computeLines(emptyList(), group = null),
                        )

                    BalanceSource.VAULT -> {
                        // The user's own ledger, read-only, never seeded,
                        // never written. The home shows what THEY persisted;
                        // empty means §11.6's copy, not a trip they never
                        // took. Scoped to the group the records carry (§5.3),
                        // and computed through the SAME recompute path the
                        // settle screen uses: a settled debt leaves this
                        // line too, or the screens disagree.
                        val store = LedgerStore(
                            vaultPath ?: throw IllegalStateException("vault source needs a path"),
                        )
                        // D-136: the selected group, never seeded. No group
                        // shows the empty state; a migrated vault waiting for
                        // your name asks for it.
                        var waiting = false
                        val current = try {
                            store.currentGroup()
                        } catch (_: NeedsYourNameException) {
                            waiting = true
                            null
                        }
                        gate = when {
                            waiting -> BalanceGate.NEEDS_NAME
                            current == null -> BalanceGate.NO_GROUPS
                            else -> BalanceGate.READY
                        }
                        named = current
                        val expenses = store.loadExpenses().filter { it.group == current?.group?.id }
                        val settlements = store.loadSettlements()
                        val redistributions = store.loadRedistributions()
                        Triple(
                            current?.group?.name to (current?.members?.size ?: 0),
                            BalanceUi.stalenessDays(
                                System.currentTimeMillis(), expenses.map { it.toInput() },
                            ),
                            current?.let {
                                SettleUi.recomputePlan(expenses, settlements, redistributions, it.group.id, it.selfId, store.removedIds(it.group.id))
                            } ?: emptyList(),
                        )
                    }

                    BalanceSource.VAULT_FIXTURES -> {
                        // The vault loop with the fixture seed: seed on first
                        // run, CLOSE, REOPEN, and compute from what was
                        // persisted. The header names the ACTUAL group (D-51),
                        // not the fallback.
                        val store = LedgerStore(
                            vaultPath ?: throw IllegalStateException("vault source needs a path"),
                        )
                        val group = store.loadGroupsOrSeed().firstOrNull()
                        val nowMs = System.currentTimeMillis()
                        val expenses = store.loadOrSeed(
                            seed = BalanceFixtures.dtoRecords(
                                group?.id ?: BalanceFixtures.ada, nowMs,
                            ),
                            groupID = group?.id,
                        )
                        Triple(
                            (group?.name) to (group?.members?.size ?: BalanceFixtures.MEMBER_COUNT),
                            BalanceUi.stalenessDays(nowMs, expenses.map { it.toInput() }),
                            BalanceUi.computeLines(expenses.map { it.toInput() }, group = group?.id),
                        )
                    }
                }
            }
            groupInfo = result.first
            staleness = result.second
            lines = result.third
        } catch (e: Exception) {
            loadError = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
        }
    }

    // D-136: "Tim (You)" for yourself on a real ledger; fixture names only
    // on the fixture sources.
    val lineNames = named?.let { group ->
        group.members.associate { it.id to LedgerStore.label(it.id, group) }
    } ?: BalanceFixtures.names
    val viewerID = named?.selfId ?: BalanceFixtures.ada

    Column(
        Modifier
            .fillMaxSize()
            .auroraBackground(dark)
            .padding(16.dp)
    ) {
        // D-136: the group chip opens the group list (many groups, with a list).
        named?.let { group ->
            if (onOpenGroups != null) {
                // D-213 (fix wave 2): iOS's SecondaryButtonStyle chip; the width stays as wide as its words.
                SecondaryAction(
                    "${group.group.name} ▾",
                    dark,
                    modifier = Modifier.semantics {
                        contentDescription = "Group: ${group.group.name}. Change group"
                    },
                    onClick = onOpenGroups,
                )
            }
        }
        if (gate == BalanceGate.NEEDS_NAME) {
            BalanceEmptyState(
                title = "Add your name",
                detail = "This ledger has a group from before names existed. " +
                    "Add your name once; you will see yourself as \"Name (You)\".",
                action = "Add your name",
                onAction = onAddYourName,
                dark = dark,
            )
        } else if (gate == BalanceGate.NO_GROUPS) {
            BalanceEmptyState(
                title = "Nothing here yet",
                detail = "Create a group, join one you were invited to, or import from another app.",
                action = "Create a group",
                onAction = onCreateGroup,
                dark = dark,
                secondary = listOfNotNull(
                    onJoin?.let { InviteCopy.JOIN_TITLE to it },
                    onImport?.let { ImportCopy.ENTRY to it },
                ),
            )
        } else when (BalanceUi.stateFor(lines)) {
            BalanceUi.State.LOADING -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("…", style = Type.bodyDim(dark))
            }

            BalanceUi.State.EMPTY -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    BalanceUi.EMPTY_LEDGER_COPY,
                    style = Type.bodyDim(dark),
                    modifier = Modifier.padding(16.dp),
                )
            }

            BalanceUi.State.CONTENT -> Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // §5.2: a frozen rate older than its 24 h cache window shows
                // the staleness indicator: §11.6's copy, WATCH-family colour
                // per §11.2, with a spoken form that reads as a sentence.
                staleness?.let { days ->
                    Text(
                        Type.kicker(if (days == 1) {
                            "Rate from 1 day ago${Separator.meta()}converted amounts may be off"
                        } else {
                            "Rate from $days days ago${Separator.meta()}converted amounts may be off"
                        }).withRunes(),
                        style = Type.microLabel(dark).copy(color = Tok.owed(dark)),
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Rate from $days ${if (days == 1) "day" else "days"} ago. " +
                                    "Converted amounts may be off."
                        },
                    )
                }

                lines?.forEach { line ->
                    SectionLabel(
                        BalanceUi.headerFor(
                            line, groupInfo.first, groupInfo.second,
                        ),
                        dark,
                        modifier = Modifier.semantics {
                            contentDescription = BalanceUi.spokenHeaderFor(
                                line, groupInfo.first, groupInfo.second,
                            )
                        },
                    )
                    Column(
                        Modifier.glassCard(dark), // D-176
                    ) {
                        BalanceLineView(
                            line = line,
                            names = lineNames,
                            viewerID = viewerID,
                            dark = dark,
                        )
                    }
                }

                // The D-91 launch smoke rides the real screen: version across
                // the FFI stays visible on every launch, proving loadLibrary
                // + checksums still pass before the first money string renders.
                Text(
                    "core via FFI: $version",
                    style = Type.numericColored(11, Tok.textFaint(dark)),
                )
            }
        }

        loadError?.let { err ->
            Text("Could not load the ledger", style = Type.bodyDim(dark))
            Text(err, style = Type.bodyDim(dark).copy(color = Tok.owed(dark)))
        }
    }
}

/** D-136: a home with no group yet, or one waiting for your name. The action
 *  is absent in the headless mounts, which have no navigation. */
@Composable
private fun BalanceEmptyState(
    title: String,
    detail: String,
    action: String,
    onAction: (() -> Unit)?,
    dark: Boolean,
    secondary: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = Type.body(dark).copy(fontSize = 20.sp))
        Text(detail, style = Type.bodyDim(dark))
        if (onAction != null) PrimaryAction(action, dark, onClick = onAction)
        secondary.forEach { (label, act) -> SecondaryAction(label, dark, onClick = act) }
    }
}

/**
 * §11.4's signature: the amount, "you are owed", a hairline rule, then the
 * per-person decomposition INLINE: one glance answers "how much" and "from
 * whom". Port of ios/Skipti/App/BalanceLineView.swift. Content stays opaque
 * (ADR-0023 §7), so §11.7's per-pair contrast verification holds; TalkBack
 * labels come from ffiMoneySpoken, never from the visible string.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BalanceLineView(
    line: BalanceLineOut,
    names: Map<String, String>,
    viewerID: String,
    dark: Boolean,
) {
    val net = line.viewerNetMinor
    val locale = AppLocale.current
    // Android's isAccessibilitySize: at elevated font scales the caption
    // drops BELOW the amount: forcing them side-by-side is exactly where
    // §11.7 said this screen breaks ("you / are / owed" in a ragged
    // column). The threshold mirrors iOS's own curve: AX1 is ~1.18x of
    // largeTitle, and Android's 1.1/1.2 steps sit in that same band.
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale >= 1.15f

    Column(Modifier.fillMaxWidth()) {
        // The amount (the numbers role since D-212) rides iOS's own curve (BalanceLineTests pins
        // largeTitle at 1.7x at the largest size): scaled, then capped
        // where the string stops fitting its card. The first 200% screenshot
        // caught the uncapped version clipping MID-DIGIT ("-₹2,182." with
        // the 50 gone), the exact defect class §11.7 names; the cap is what
        // keeps every digit on screen. Known limit, recorded honestly: a
        // much longer headline could still overflow one line at 200%:
        // width-aware shrinking (iOS's minimumScaleFactor) is §4 material.
        val amountStyle = Type.numeric(40, dark = dark)
            .copy(color = BalanceUi.tokenFor(net)(dark))
            .copy(fontSize = (40 * min(fontScale, 1.4f)).sp)

        val amount: @Composable (Modifier) -> Unit = { mod ->
            Text(
                BalanceUi.moneyFormat(net, line.currencyCode, locale),
                style = amountStyle,
                maxLines = 1,
                modifier = mod.semantics {
                    contentDescription = BalanceUi.moneySpoken(net, line.currencyCode, locale)
                },
            )
        }
        val caption: @Composable (Modifier) -> Unit = { mod ->
            Text(
                BalanceUi.captionFor(net),
                style = Type.bodyDim(dark),
                // Hidden from accessibility: the spoken label already says it.
                modifier = mod.clearAndSetSemantics {},
            )
        }

        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                amount(Modifier)
                caption(Modifier)
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                // Both opt in: a lone alignByBaseline child becomes the
                // row's baseline itself, which parks the caption at the
                // amount's TOP: iOS aligns .firstTextBaseline, and so
                // does this row once both declare.
                amount(Modifier.alignByBaseline())
                // D-221 (L-D217-7): the caption at the row's end, as iOS's `Spacer(minLength: 12)` puts it; the
                // padding keeps the 12 dp minimum when the amount fills the row.
                Spacer(Modifier.weight(1f))
                caption(Modifier.alignByBaseline().padding(start = 12.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Tok.hairline(dark))
        )

        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (stacked) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (stacked) 14.dp else 18.dp),
        ) {
            line.entries.forEach { entry ->
                val name = names[entry.id] ?: entry.id
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "$name: " +
                            BalanceUi.moneySpoken(entry.minorUnits, line.currencyCode, locale)
                    },
                ) {
                    Text(
                        Type.kicker(name),
                        style = Type.microLabel(dark).copy(color = Tok.textDim(dark)),
                    )
                    Text(
                        BalanceUi.moneyDecomposition(entry.minorUnits, line.currencyCode, locale),
                        style = Type.numericColored(15, Tok.text(dark), weight = FontWeight.Normal),
                    )
                }
            }
        }
    }
}
