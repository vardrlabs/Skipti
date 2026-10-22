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

// D-260 (G-13, decision 269): a proof surface moved out of src/main, Debug builds only (src/release has none).

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
