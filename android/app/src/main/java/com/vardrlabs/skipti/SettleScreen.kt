package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.RedistributionInput
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.TransferOut

/**
 * Computed data for §5.8's settle-up screen: the pure decisions the JVM
 * tests drive against the REAL core (same discipline as BalanceUi /
 * ExpenseListUi / AddExpenseUi). THE LEGAL BOUNDARY LIVES IN THESE STRINGS:
 * SKIPTI records that a debt was settled; it never moves money, never holds
 * funds, never links an account (ADR-0008, §13.2). Everything here passes
 * §11.6's copy rules; the explainer sentence is verbatim.
 */
object SettleUi {
    /** §11.6, verbatim: the sentence that keeps SKIPTI a notebook. */
    const val EXPLAINER = "Marks this as paid here. It does not move money."

    /** Sentence-position name: the viewer is "you", never "You". */
    fun you(id: String, viewer: String, names: Map<String, String>): String =
        if (id == viewer) "you" else (names[id] ?: id)

    /** §11.6's verb rule: each branch keeps its own progression: Settle →
     *  Settling…, Mark received → Receiving…. Never one verb morphing into
     *  the other's. */
    fun buttonLabel(youOwe: Boolean, settling: Boolean): String = when {
        settling && youOwe -> "Settling…"
        settling -> "Receiving…"
        youOwe -> "Settle"
        else -> "Mark received"
    }

    /** §11.6: sentence case, terse; the verb agrees with its subject and
     *  the viewer's subject is "you". The visible copy and the spoken label
     *  say the SAME words, including the verb (iOS's spoken label said
     *  "you pays Rahul"; agreement is the requirement here). */
    fun planRow(from: String, to: String, viewer: String, names: Map<String, String>): String {
        val verb = if (from == viewer) "pay" else "pays"
        return "${you(from, viewer, names)} $verb ${you(to, viewer, names)}"
    }

    fun planSpoken(from: String, to: String, viewer: String, names: Map<String, String>): String =
        "${planRow(from, to, viewer, names)}."

    /** The debt row's micro-label frame, viewer-framed (D-47). */
    fun debtLabel(entry: ShareOut, viewer: String, names: Map<String, String>): String =
        if (entry.minorUnits < 0) {
            "You owe ${names[entry.id] ?: entry.id}"
        } else {
            "${names[entry.id] ?: entry.id} owes you"
        }

    fun debtSpoken(entry: ShareOut, viewer: String, names: Map<String, String>): String =
        if (entry.minorUnits < 0) {
            "You owe ${names[entry.id] ?: entry.id}."
        } else {
            "${names[entry.id] ?: entry.id} owes you."
        }

    private const val VIEWER_KEY = "__viewer__"

    /**
     * §5.8's plan, pinned as a property rather than reimplemented: the
     * transfers CONSERVE the ledger's absolute net positions: applying
     * them zeroes every net (§5.6's net formula applied by the CALLER; the
     * viewer-frame line alone cannot reconstruct an uninvolved viewer's
     * ledger, which is why this takes the nets). A settlement-shaped or
     * simplification-shaped bug corrupts exactly this. ≤n−1 legs is core's
     * own guarantee (§5.6), pinned separately by a golden where naive
     * pairwise would need more transfers.
     */
    fun planConserves(nets: Map<String, Long>, transfers: List<TransferOut>): Boolean {
        val applied = nets.toMutableMap()
        for (t in transfers) {
            applied[t.from] = (applied[t.from] ?: 0L) + t.amountMinor
            applied[t.to] = (applied[t.to] ?: 0L) - t.amountMinor
        }
        return applied.values.all { it == 0L }
    }

    /**
     * The recomputed plan, THE ONE PATH: the screen uses this and so do the
     * JVM tests: a settlement-shaped bug (or a Kotlin patch that skips the
     * settlements) turns the same test red that the screen depends on.
     * Settlements come from the vault; the settled state is what core says,
     * never a UI flag.
     */
    fun recomputePlan(
        expenses: List<ExpenseDTO>,
        settlements: List<SettlementDTO>,
        redistributions: List<RedistributionInput>,
        groupId: String?,
        viewer: String,
        removed: List<String> = emptyList(),
    ): List<BalanceLineOut> =
        BalanceUi.sortedLines(
            ffiBalanceLine(
                expenses.map { it.toInput() },
                settlements.map { it.toInput() },
                redistributions,
                removed,
                viewer,
                groupId,
            )
        )

    /**
     * The settlement a Settle tap records: between the VIEWER (this device's
     * self, D-136; it was the fixture Ada) and the other person, in the
     * direction the viewer-framed entry says. Negative means you owe them.
     */
    fun settlementFor(entry: ShareOut, currency: String, group: String?, viewer: String): SettlementDTO {
        val youOwe = entry.minorUnits < 0
        return SettlementDTO(
            from = if (youOwe) viewer else entry.id,
            to = if (youOwe) entry.id else viewer,
            amountMinor = abs(entry.minorUnits),
            currencyCode = currency,
            group = group,
        )
    }
}

/**
 * §5.8's settle-up screen: port of ios/Skipti/App/SettleScreen.swift, the
 * screen where the product's legal boundary lives. SKIPTI records that a
 * debt was settled; it never moves money, never holds funds, never links
 * an account (ADR-0008, §13.2). Debts come from `ffiBalanceLine` in the
 * VIEWER frame (D-47); one SECTION per currency the ledger holds (D-50);
 * every amount through the FFI. Recording goes through the straumr record
 * path (LedgerStore.appendSettlement), never a UI-local flag: the settled
 * state is the row LEAVING the recomputed plan.
 */
@Composable
fun SettleScreen(vaultPath: String) {
    val dark = isSystemInDarkTheme()
    var lines by remember { mutableStateOf<List<BalanceLineOut>?>(null) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var groupInfo by remember { mutableStateOf<Pair<String?, Int>>(null to 0) }
    // D-136: the viewer is this device's self; names come from the group.
    var viewer by remember { mutableStateOf("") }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var staleness by remember { mutableStateOf<Int?>(null) }
    var settlingKey by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // The recomputed plan: expenses AND settlements from the vault, through
    // core: the settled state is what the recomputation says, never a
    // local flag.
    val recompute: suspend (String) -> Unit = { path ->
        val store = LedgerStore(path)
        val expenses = store.loadExpenses()
        val loaded = store.loadSettlements()
        val moved = store.loadRedistributions()
        // D-136: the selected group (it was "the first group with an expense").
        val current = try {
            store.currentGroup()
        } catch (_: NeedsYourNameException) {
            throw IllegalStateException("Add your name first.")
        }
        groupId = current?.group?.id
        groupInfo = current?.group?.name to (current?.members?.size ?: 0)
        viewer = current?.selfId ?: ""
        names = current?.let { group ->
            group.members.associate { it.id to LedgerStore.label(it.id, group) }
        } ?: emptyMap()
        val inputs = expenses.filter { it.group == current?.group?.id }.map { it.toInput() }
        staleness = BalanceUi.stalenessDays(System.currentTimeMillis(), inputs)
        lines = current?.let { SettleUi.recomputePlan(expenses, loaded, moved, it.group.id, it.selfId, store.removedIds(it.group.id)) }
            ?: emptyList()
    }

    // D-178: read again when sync brings records.
    LaunchedEffect(vaultPath, SyncStatus.revision) {
        try {
            withContext(Dispatchers.IO) { recompute(vaultPath) }
        } catch (e: Exception) {
            loadError = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .auroraBackground(dark)
            .padding(16.dp)
    ) {
        when {
            loadError != null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Could not load the ledger", style = Type.body(dark))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        loadError!!,
                        style = Type.bodyDim(dark).copy(color = Tok.owed(dark)),
                    )
                }
            }

            lines == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("…", style = Type.bodyDim(dark))
            }

            else -> Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // §11.6, verbatim: the sentence that keeps SKIPTI a
                // notebook instead of a regulated entity.
                Text(
                    SettleUi.EXPLAINER,
                    style = Type.bodyDim(dark),
                    modifier = Modifier.semantics {
                        contentDescription = SettleUi.EXPLAINER
                    },
                )

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

                confirmation?.let {
                    Text(
                        it,
                        style = Type.bodyDim(dark).copy(color = Tok.owing(dark)),
                        modifier = Modifier.semantics { contentDescription = it },
                    )
                }

                saveError?.let {
                    Text(
                        it,
                        style = Type.bodyDim(dark).copy(color = Tok.owed(dark)),
                        modifier = Modifier.semantics { contentDescription = it },
                    )
                }

                lines?.forEach { line ->
                    SectionLabel("Settle in ${line.currencyCode}${Separator.meta()}${groupInfo.first ?: ""}", dark)
                    line.entries.forEach { entry ->
                        DebtRow(
                            entry = entry,
                            currency = line.currencyCode,
                            settling = settlingKey == "${line.currencyCode}/${entry.id}",
                            group = groupId,
                            vaultPath = vaultPath,
                            viewer = viewer,
                            names = names,
                            dark = dark,
                            onRecord = {
                                // D-235 (H1): one payment at a time; a second tap before the row redraws records nothing.
                                if (settlingKey != null) return@DebtRow
                                settlingKey = "${line.currencyCode}/${entry.id}"
                                saveError = null
                                recordSettlement(
                                    SettleUi.settlementFor(entry, line.currencyCode, groupId, viewer),
                                    vaultPath,
                                    onDone = {
                                        confirmation = SettleAllCopy.recorded(names[entry.id] ?: entry.id)
                                        recompute(vaultPath)
                                        settlingKey = null
                                    },
                                    onFailed = { message ->
                                        // D-136: this failure used to vanish and
                                        // leave the button on "Settling…" for good.
                                        saveError = message
                                        settlingKey = null
                                    },
                                )
                            },
                        )
                    }
                    PlanSection(line, viewer, names, dark)
                }
            }
        }
    }
}

/** The save path, owned by the screen (it owns the settling state): persist
 *  through the straumr record path, then recompute from the ledger. */
private fun recordSettlement(
    settlement: SettlementDTO,
    vaultPath: String,
    onDone: suspend () -> Unit,
    onFailed: (String) -> Unit,
) {
    fun describe(e: Exception) = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            LedgerStore(vaultPath).appendSettlement(settlement)
            // §10.3: saved locally first; sync attempted fire-and-forget.
            CloudSync.ledgerChanged(vaultPath)
        } catch (e: Exception) {
            onFailed("Could not record the settlement. ${describe(e)}")
            return@launch
        }
        // Nothing may escape this coroutine: on Android an escape crashes the
        // process (the FireAndForgetGuard lesson).
        try {
            onDone()
        } catch (e: Exception) {
            onFailed("Recorded, but the ledger could not be reloaded. ${describe(e)}")
        }
    }
}

@Composable
private fun DebtRow(
    entry: ShareOut,
    currency: String,
    settling: Boolean,
    group: String?,
    vaultPath: String,
    viewer: String,
    names: Map<String, String>,
    dark: Boolean,
    onRecord: () -> Unit,
) {
    val youOwe = entry.minorUnits < 0
    val amount = abs(entry.minorUnits)
    val other = names[entry.id] ?: entry.id
    val amountText = BalanceUi.moneyFormat(amount, currency, AppLocale.current)
    val spoken = BalanceUi.moneySpoken(amount, currency, AppLocale.current)
    // §11.7 names this screen as where layouts break: at large font scales
    // the action drops BELOW the amount (full width, no mid-word clipping),
    // never squeezed beside it: iOS's first AX5 pass on THIS screen caught
    // the amount wrapping mid-digit and the button clipping mid-word.
    val stacked = LocalDensity.current.fontScale >= 1.15f

    val meta: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                Type.kicker(SettleUi.debtLabel(entry, viewer, names)),
                style = Type.microLabel(dark).copy(color = Tok.textDim(dark)),
            )
            Text(
                amountText,
                style = Type.numericColored(17, Tok.text(dark)),
                maxLines = 1,
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Tok.surface(dark), ThemeArt.panelShape(Appearance.theme, 12))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (stacked) 12.dp else 0.dp),
    ) {
        if (stacked) {
            meta()
            Box(Modifier.fillMaxWidth()) {
                ActionButton(onRecord, youOwe, other, spoken, settling, dark)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { meta() }
                Spacer(Modifier.width(12.dp))
                ActionButton(onRecord, youOwe, other, spoken, settling, dark)
            }
        }
    }
}

@Composable
private fun ActionButton(
    onRecord: () -> Unit,
    youOwe: Boolean,
    other: String,
    spoken: String,
    settling: Boolean,
    dark: Boolean,
) {
    val label = SettleUi.buttonLabel(youOwe, settling)
    if (settling) {
        Text(label, style = Type.bodyDim(dark))
    } else {
        PrimaryAction(
            label = label,
            dark = dark,
            modifier = Modifier.semantics {
                // §11.6's sentence rides EVERY button's accessibility
                // label: the boundary stated at the point of action.
                contentDescription =
                    (if (youOwe) "Settle debt to $other." else "Mark received from $other.") +
                        " $spoken. ${SettleUi.EXPLAINER}"
            },
            onClick = onRecord,
        )
    }
}

@Composable
private fun PlanSection(line: BalanceLineOut, viewer: String, names: Map<String, String>, dark: Boolean) {
    if (line.transfers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Fastest plan", dark)
        line.transfers.forEach { t ->
            PlanRow(t, line.currencyCode, viewer, names, dark)
        }
    }
}

@Composable
private fun PlanRow(
    t: TransferOut,
    currencyCode: String,
    viewer: String,
    names: Map<String, String>,
    dark: Boolean,
) {
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale >= 1.15f
    val leg = SettleUi.planRow(t.from, t.to, viewer, names)
    val amount = BalanceUi.moneyFormat(t.amountMinor, currencyCode, AppLocale.current)
    val spokenAmount = BalanceUi.moneySpoken(t.amountMinor, currencyCode, AppLocale.current)
    if (stacked) {
        Column(
            Modifier.semantics {
                contentDescription =
                    "${SettleUi.planSpoken(t.from, t.to, viewer, names)} " +
                        "$spokenAmount."
            }
        ) {
            Text(leg, style = Type.bodyDim(dark))
            Text(
                amount,
                style = Type.numericColored(15, Tok.text(dark), weight = FontWeight.Normal),
                maxLines = 1,
            )
        }
    } else {
        Row {
            Text(leg, style = Type.bodyDim(dark), modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            Text(
                amount,
                style = Type.numericColored(15, Tok.text(dark), weight = FontWeight.Normal),
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
